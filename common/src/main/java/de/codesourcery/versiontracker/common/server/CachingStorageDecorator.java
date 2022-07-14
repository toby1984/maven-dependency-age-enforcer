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

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactMap;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.VersionInfo;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * A decorator that wraps a {@link IVersionStorage} and 
 * caches <b>all</b> the results in-memory.
 * 
 * Using this decorator obviously does not work well if you have a <b>lot</b> of artifacts
 * that need to be handled...
 * 
 * This class is thread-safe.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class CachingStorageDecorator implements IVersionStorage
{
    private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger( CachingStorageDecorator.class );

    private static final class CollectingVisitor implements Consumer<VersionInfo> {

        public final List<VersionInfo> list = new ArrayList<>();

        private final boolean doCopy;

        public CollectingVisitor(boolean copy) {
            this.doCopy = copy;
        }

        @Override
        public void accept(VersionInfo t) 
        {
            if ( doCopy ) {
                list.add( t.copy() );
            } else {
                list.add( t );
            }
        }
    }

    private final IVersionStorage delegate;

    // @GuardedBy( cleanCache )
    private boolean initialized;

    // @GuardedBy( cleanCache )
    private final ArtifactMap<VersionInfo> dirtyCache = new ArtifactMap<>();

    // @GuardedBy( cleanCache )
    private final ArtifactMap<VersionInfo> cleanCache = new ArtifactMap<>();
    
    // @GuardedBy( cleanCache )
    private long lazyFlushes = 0;

    private volatile Duration cacheFlushInterval = Duration.ofSeconds( 10 );

    private final Object THREAD_LOCK = new Object();

    // @GuardedBy( THREAD_LOCK )
    private CacheFlushThread thread;

    private volatile boolean shutdown;
    
    protected final class CacheFlushThread extends Thread
    {
        private final Object SLEEP_LOCK = new Object();
        private final CountDownLatch stopLatch = new CountDownLatch(1);

        public CacheFlushThread() {
        }

        @Override
        public void run() 
        {
            LOG.info("run(): Cache flushing thread started");
            boolean regularShutdown = false;
            try {
                while ( ! shutdown ) 
                {
                    flushCache();
                    try 
                    {
                        synchronized(SLEEP_LOCK) {
                            SLEEP_LOCK.wait( cacheFlushInterval.toMillis() );
                        }
                    }
                    catch(Exception e) 
                    {
                        Thread.currentThread().interrupt();
                    }
                }
                regularShutdown = true;
            } 
            catch (Exception e1) {
                LOG.error("run(): Caught exception "+e1.getMessage(),e1);
            } 
            finally 
            {
                stopLatch.countDown();
                LOG.info("run(): Cache flushing thread finished (regular shutdown="+regularShutdown+")");
                if ( ! regularShutdown ) {
                    final Thread t = new Thread( () -> 
                    {
                        LOG.error("run(): Thread finished unexpectedly, restarting in 60 seconds");
                        try {
                            Thread.sleep( 60*1000 );
                        } catch(Exception e) {
                            // can't help it
                        }
                        startThread();
                    });
                    t.setDaemon( true );
                    t.setName("cache-flush-restarter");
                    t.start();
                }
            }
        }

        public void shutdown() throws InterruptedException 
        {
            LOG.info("shutdown(): Terminating cache flush thread");
            if ( isAlive() ) 
            {
                shutdown = true;
                synchronized(SLEEP_LOCK) {
                    SLEEP_LOCK.notifyAll();
                }
                stopLatch.await();
            }
        }        
    }
    
    public CachingStorageDecorator(IVersionStorage delegate) {
        Validate.notNull(delegate,"delegate must not be NULL");
        this.delegate = delegate;
    }
    
    private void flushCache() throws IOException 
    {
        synchronized(cleanCache) 
        {
            if ( ! dirtyCache.isEmpty() ) 
            {
                final long start = System.currentTimeMillis();
                final CollectingVisitor v = new CollectingVisitor(false);
                dirtyCache.visitValues( v );
                delegate.saveOrUpdate( v.list );
                cleanCache.putAll( dirtyCache );
                dirtyCache.clear();
                
                lazyFlushes += v.list.size();
                if ( LOG.isDebugEnabled() ) {
                    final long elapsed = System.currentTimeMillis() - start;
                    LOG.debug("flushCache(): Flushed "+v.list.size()+" items in "+elapsed+" ms (total flushed items is now: "+lazyFlushes+")");
                }
            }
        }
    }

    @Override
    public StorageStatistics getStatistics() {
        try {
            maybeInit();
        }
        catch ( IOException e ) {
            throw new RuntimeException( e );
        }
        return delegate.getStatistics();
    }

    public void startThread()
    {
        synchronized( THREAD_LOCK ) 
        {
            if ( ! shutdown ) 
            {
                thread = new CacheFlushThread();
                thread.start();
            }
        }
    }

    private void maybeInit() throws IOException 
    {
        synchronized(cleanCache) 
        {
            if ( ! initialized ) {
                cleanCache.clear();
                delegate.getAllVersions().forEach( v ->
                {
                    cleanCache.put( v.artifact.groupId, v.artifact.artifactId, v );
                });
                LOG.info("maybeInit(): Loaded "+cleanCache.size()+" entries from underlying storage");
                initialized = true;
            }
        }
    }

    @Override
    public List<VersionInfo> getAllVersions() throws IOException 
    {
        synchronized(cleanCache) 
        {
            maybeInit();
            final ArtifactMap<VersionInfo> uniqueSet = new ArtifactMap<>( cleanCache );
            uniqueSet.putAll( dirtyCache );
            final CollectingVisitor collector = new CollectingVisitor(true);
            uniqueSet.visitValues( collector);
            return collector.list;               
        }
    }

    @Override
    public Optional<VersionInfo> getVersionInfo(Artifact artifact) throws IOException 
    {
        synchronized(cleanCache) 
        {
            maybeInit();
            VersionInfo result = dirtyCache.get( artifact.groupId, artifact.artifactId );
            if ( result == null ) {
                result = cleanCache.get( artifact.groupId, artifact.artifactId );
            }
            return result == null ? Optional.empty() : Optional.of(result.copy());
        }        
    }

    @Override
    public void saveOrUpdate(VersionInfo info) {
        synchronized(cleanCache) 
        {
            dirtyCache.put( info.artifact.groupId, info.artifact.artifactId, info );
        }        
    }    

    @Override
    public void saveOrUpdate(List<VersionInfo> data) {
        synchronized(cleanCache) 
        {
            for ( VersionInfo info : data ) 
            {
                dirtyCache.put( info.artifact.groupId, info.artifact.artifactId, info );
            }
        }
    }

    @Override
    public void close() throws Exception 
    {
        LOG.info("close(): Initiating cache flush upon shutdown...");
        flushCache();
        LOG.info("close(): Cache flushed.");
        shutdown = true;
        synchronized( THREAD_LOCK ) 
        {
            if ( thread != null ) 
            {
                try {
                    thread.shutdown();
                } finally {
                    thread = null;
                }
            }
        }
    }

    public void setCacheFlushInterval(Duration cacheFlushInterval) {
        this.cacheFlushInterval = cacheFlushInterval;
    }
}