/**
 * Copyright 2018 Tobias Gierke <tobias.gierke@code-sourcery.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.codesourcery.versiontracker.common.server;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.VersionInfo;
import de.codesourcery.versiontracker.common.server.SharedLockCache.ThrowingRunnable;

/**
 * This class is responsible for requesting and periodically refreshing artifact metadata as well
 * as providing this metadata to clients using the {@link #getVersionInfo(Artifact)} method. 
 *
 * This class is thread-safe.
 * 
 * @author tobias.gierke@code-sourcery.de
 */
public class VersionTracker implements AutoCloseable
{
    private static final Logger LOG = LogManager.getLogger(VersionTracker.class);

    private final IVersionStorage versionStorage;
    private final IVersionProvider versionProvider;

    private final Object THREAD_POOL_LOCK = new Object();

    // @GuardedBy( THREAD_POOL_LOCK )
    private int maxConcurrentThreads = 1;

    // @GuardedBy( THREAD_POOL_LOCK )
    private ThreadPoolExecutor threadPool;

    private final SharedLockCache lockCache;    

    private final ThreadFactory threadFactory = new ThreadFactory() 
    {
        private final ThreadGroup threadGroup = new ThreadGroup(Thread.currentThread().getThreadGroup(),"versiontracker-request-threads" );
        private final AtomicInteger threadId = new AtomicInteger(0); 
        public Thread newThread(Runnable r) 
        {
            final Thread t = new Thread(threadGroup,r);
            t.setDaemon( true );
            t.setName("versiontracker-request-thread-"+threadId.incrementAndGet());
            return t;
        }
    };

    public VersionTracker(IVersionStorage versionStorage,IVersionProvider versionProvider,SharedLockCache lockCache) {
        Validate.notNull(versionProvider,"versionProvider must not be NULL");
        Validate.notNull(versionStorage,"versionStorage must not be NULL");
        Validate.notNull(lockCache,"lockCache must not be NULL");

        this.versionProvider = versionProvider;
        this.versionStorage = versionStorage;
        this.lockCache = lockCache;
    }    

    /**
     * Try to retrieve version information for a given artifact.
     * 
     * @param artifacts
     * @param isOutdated 
     * @return
     * @throws InterruptedException 
     */
    public Map<Artifact,VersionInfo> getVersionInfo(List<Artifact> artifacts,Predicate<Optional<VersionInfo>> isOutdated) throws InterruptedException
    {
        final Map<Artifact,VersionInfo> resultMap = new HashMap<>();

        final ZonedDateTime now = ZonedDateTime.now();
        final DynamicLatch stopLatch = new DynamicLatch();

        for ( Artifact artifact : artifacts ) 
        {
            try 
            {
                lockCache.doWhileLocked( artifact, () -> 
                {
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("getVersionInfo(): Looking for "+artifact+" in version storage");
                    }
                    final Optional<VersionInfo> result = versionStorage.getVersionInfo( artifact );
                    if( ! result.isPresent() || isOutdated.test( result ) ) 
                    {
                        LOG.debug("getVersionInfo(): Got "+(result.isPresent()? "outdated":"no")+" metadata for "+artifact+" yet,fetching it");
                        updateArtifact(artifact, result, resultMap, stopLatch, now, isOutdated);                    
                    } else {
                        LOG.debug("getVersionInfo(): [from storage] "+result.get());

                        synchronized(resultMap) {
                            resultMap.put(artifact,result.get().copy());
                        }
                        versionStorage.updateLastRequestDate(artifact,now);
                    }                
                });
            } catch (Exception e) {
                LOG.error("getVersionInfo(): Caught unexpected exception "+e.getMessage()+" while handling "+artifact,e);
                throw new RuntimeException("Uncaught exception "+e.getMessage()+" while handling "+artifact,e);
            }
        }
        
        stopLatch.await();
        
        return resultMap;
    }

    private void updateArtifact(Artifact artifact, 
            Optional<VersionInfo> existing,
            Map<Artifact, VersionInfo> resultMap,
            DynamicLatch stopLatch, 
            ZonedDateTime now, 
            Predicate<Optional<VersionInfo>> isOutdated) throws IOException 
    {
        stopLatch.inc();
        boolean submitted = false;
        try 
        {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("updateArtifact(): About to submit task for "+artifact);
            }             
            submit( () -> 
            {
                try 
                {
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("updateArtifact(): Waiting to lock "+artifact);
                    }                    
                    lockCache.doWhileLocked(artifact,() -> 
                    {
                        if ( LOG.isDebugEnabled() ) {
                            LOG.debug("updateArtifact(): Got lock for "+artifact);
                        }                         
                        final VersionInfo newInfo; 
                        if ( existing.isPresent() ) 
                        {
                            if ( LOG.isDebugEnabled() ) {
                                LOG.debug("updateArtifact(): [outdated] Trying to update metadata for "+artifact);
                            }
                            newInfo = existing.get();
                            newInfo.lastRequestDate = now;
                        } 
                        else 
                        {
                            if ( LOG.isDebugEnabled() ) {
                                LOG.debug("updateArtifact(): [missing] Trying to update metadata for "+artifact);
                            }                                
                            newInfo = new VersionInfo();
                            newInfo.creationDate = now;
                            newInfo.artifact = artifact;
                            newInfo.lastRequestDate = now;
                        }

                        synchronized(resultMap) {
                            resultMap.put(artifact,newInfo.copy());
                        } 

                        try 
                        {
                            versionProvider.update( newInfo );
                        }
                        catch (Exception e) 
                        {
                            if ( LOG.isDebugEnabled() ) {
                                LOG.error("updateArtifact(): Caught "+e.getMessage()+" while updating "+artifact,e);
                            } else {
                                LOG.error("updateArtifact(): Caught "+e.getMessage()+" while updating "+artifact+": "+e.getMessage());
                            }
                        } 
                        finally {
                            versionStorage.saveOrUpdate( newInfo );                            
                        }
                    });
                } 
                catch (Exception e) 
                {
                    LOG.error("updateArtifact(): Caught "+e.getMessage()+" while updating "+artifact,e);
                } 
                finally 
                {
                    stopLatch.dec();
                }
            });
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("updateArtifact(): Submitted task for "+artifact);
            }                 
            submitted = true;
        } 
        finally 
        {
            if ( ! submitted ) {
                stopLatch.dec();
            }
        }
    }

    protected static final class DynamicLatch 
    {
        private int count = 0;

        public void inc() {
            synchronized(this) {
                count++;
            }
        }

        public void dec() 
        {
            synchronized(this) 
            {
                if ( count == 0 ) {
                    LOG.error("dec(): Internal error, count < 0");
                    throw new IllegalStateException("count < 0 ?");
                }
                count--;
                notifyAll();
            }
        }        

        public void await() throws InterruptedException 
        {
            synchronized(this) 
            {
                while ( count > 0 ) 
                {
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("await(): Waiting for "+count+" threads to finish");
                    }
                    wait();
                }
            }
        }
    }

    private void submit(Runnable r) 
    {
        synchronized( THREAD_POOL_LOCK ) 
        {
            if ( threadPool == null ) 
            {
                LOG.info("setMaxConcurrentThreads(): Using "+maxConcurrentThreads+" threads to retrieve artifact metadata.");                
                final BlockingQueue<Runnable> workingQueue = new ArrayBlockingQueue<Runnable>(200);
                threadPool = new ThreadPoolExecutor( maxConcurrentThreads, maxConcurrentThreads, 60 , TimeUnit.SECONDS,
                        workingQueue,threadFactory, new ThreadPoolExecutor.CallerRunsPolicy() );
            }
            threadPool.submit( r );
        }
    }

    /**
     * Sets the maximum number of threads to use when retrieving artifact metdata.
     * 
     * Note that the concurrency on a HTTP request level is determined by the
     * {@link IVersionProvider}, not by this setting.
     * 
     * @param maxConcurrentThreads
     */
    public void setMaxConcurrentThreads(int maxConcurrentThreads)
    {
        if ( maxConcurrentThreads < 1 ) 
        {
            throw new IllegalArgumentException("maxConcurrentThreads needs to be >= 1");
        }
        synchronized( THREAD_POOL_LOCK) 
        {
            final boolean poolChanged = this.maxConcurrentThreads != maxConcurrentThreads;
            this.maxConcurrentThreads = maxConcurrentThreads;
            if ( poolChanged && threadPool != null ) 
            {
                threadPool.shutdown();
                threadPool = null;
            }
        }
    }

    public int getMaxConcurrentThreads()
    {
        synchronized( THREAD_POOL_LOCK ) 
        {
            return maxConcurrentThreads;
        }
    }

    @Override
    public void close() 
    {
        synchronized( THREAD_POOL_LOCK ) 
        {
            if ( threadPool != null ) 
            {
                LOG.debug("close(): Shutting down thread pool");
                threadPool.shutdown();
                threadPool = null;
            }
        }
    }
}