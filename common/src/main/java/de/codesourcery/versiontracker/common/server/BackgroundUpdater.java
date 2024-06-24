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
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;
import de.codesourcery.versiontracker.common.server.SharedLockCache.ThrowingRunnable;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background process that periodically wakes up and initiates 
 * a metadata update for each stale {@link VersionInfo}. 
 *
 * @author tobias.gierke@code-sourcery.de
 * 
 * @see IVersionStorage#isStaleVersion(VersionInfo, Duration, Duration, ZonedDateTime)
 */
public class BackgroundUpdater implements IBackgroundUpdater {

    private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger( BackgroundUpdater.class );
    
    private final SharedLockCache artifactLocks;
    
    private final Object THREAD_LOCK = new Object();
    
    // GuardedBy( THREAD_LOCK )    
    private BGThread thread;
    
    private volatile boolean shutdown;

    // GuardedBy( statistics )
    private final Statistics statistics = new Statistics();
    
    public volatile Configuration configuration = new Configuration();
    
    private final IVersionStorage storage;
    private final IVersionProvider provider;
    
    private final ThreadPoolExecutor threadPool;    
    
    protected final class BGThread extends Thread 
    {
        private final Object SLEEP_LOCK = new Object();        
        private final CountDownLatch stopLatch = new CountDownLatch(1);
        
        public BGThread() 
        {
            setDaemon(true);
            setName("background-update-thread");
        }
        
        public void run() 
        {
            LOG.info("run(): Background thread started.");
            boolean regularShutdown = false;
            try {
                while ( ! shutdown ) 
                {
                    doUpdate();
                    synchronized( SLEEP_LOCK ) 
                    {
                        SLEEP_LOCK.wait( configuration.getBgUpdateCheckInterval().toMillis() );
                    }
                }
                regularShutdown = true; 
            } 
            catch (Exception e) 
            {
                LOG.error("run(): Caught unexpected exception "+e.getMessage(),e);
            } 
            finally 
            {
                stopLatch.countDown();
                LOG.info("run(): Background thread about to stop (regular shutdown="+regularShutdown+")");                      
                if ( ! regularShutdown ) {
                  final Thread t = new Thread( () -> 
                  {
                      LOG.warn("run(): Thread died unexpectedly, restarting in 60 seconds");
                      try {
                          Thread.sleep( 60*1000 );
                      } catch(Exception e) {
                          Thread.currentThread().interrupt();
                      }
                      LOG.warn("run(): Restarting thread that died unexpectedly...");
                      startThread();
                  });
                  t.setDaemon( true );
                  t.setName("bg-restarter-thread");
                  t.start();
                }
            }
        }
        
        public void shutdown() throws InterruptedException 
        {
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

    public BackgroundUpdater(IVersionStorage storage, IVersionProvider provider,SharedLockCache artifactLocks) 
    {
        Validate.notNull(storage,"storage must not be NULL");
        Validate.notNull(provider,"provider must not be NULL");
        Validate.notNull(artifactLocks,"artifactLocks must not be NULL");
        this.storage = storage;
        this.provider = provider;
        this.artifactLocks = artifactLocks;
        
        final int threadCount = Runtime.getRuntime().availableProcessors();
        final ThreadFactory threadFactory = new ThreadFactory() 
        {
            private final AtomicInteger THREAD_ID = new AtomicInteger(0);
            
            @Override
            public Thread newThread(Runnable r) 
            {
                final Thread t = new Thread(r);
                t.setName("bg-updater-thread-"+THREAD_ID.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        final BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>( 100 );
        this.threadPool = new ThreadPoolExecutor( threadCount, threadCount, 60, TimeUnit.SECONDS, workQueue, threadFactory, new ThreadPoolExecutor.CallerRunsPolicy() );
    }
    
    private void doUpdate() throws Exception
    {
        final List<VersionInfo> infos = storage.getAllStaleVersions( configuration.getMinUpdateDelayAfterSuccess(),
            configuration.getMinUpdateDelayAfterFailure(), ZonedDateTime.now() );
        LOG.info("doUpdate(): Updating "+infos.size()+" stale artifacts");
        for (VersionInfo info : infos) 
        {
            doUpdate(info);
        }
    }

    private boolean requiresUpdate(VersionInfo info)
    {
        Validate.notNull( info, "info must not be null" );
        boolean result = IVersionStorage.isStaleVersion(
                info,
            configuration.getMinUpdateDelayAfterSuccess(),
            configuration.getMinUpdateDelayAfterFailure(),
                ZonedDateTime.now() );
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("requiresUpdate(): ["+(result?"YES":"NO")+"] "+info.artifact);
        }
        return result;
    }

    @Override
    public boolean requiresUpdate(VersionInfo info, Artifact artifact) {

        if ( requiresUpdate( info ) ) {
            return true;
        }
        boolean updateNeeded = false;
        if ( info.versions.stream().anyMatch( x -> ! x.hasReleaseDate() ) ) {
            updateNeeded = true;
        }
        if ( info.latestReleaseVersion != null && ! info.latestReleaseVersion.hasReleaseDate() ) {
            updateNeeded = true;
        }
        if ( info.latestSnapshotVersion != null && ! info.latestSnapshotVersion.hasReleaseDate() ) {
            updateNeeded = true;
        }
        final Optional<Version> version = info.getVersion( artifact.version );
        if ( info.versions.isEmpty() || version.isPresent() && ! version.get().hasReleaseDate() ) {
            updateNeeded = true;
        }
        if ( updateNeeded ) {
            // note: vi.lastPolledDate() cannot be NULL here as requiresUpdate(Optional<VersionInfo>)
            //       would've returned true in this case and we bail out early above
            final Duration timeSinceLastUpdate = Duration.between( info.lastPolledDate(), ZonedDateTime.now() );

            Duration duration = configuration.getMinUpdateDelayAfterSuccess();
            boolean lastPollFailed = info.lastFailureDate != null && info.lastPolledDate() == info.lastFailureDate;
            if ( lastPollFailed) {
                duration = configuration.getMinUpdateDelayAfterFailure();
            }
            if ( timeSinceLastUpdate.compareTo( duration ) < 0 ) {
                LOG.debug( "Not performing metadata update as last poll " + (lastPollFailed ? "failed" : "succeeded") + " at " + info.lastPolledDate() + " " +
                    "which happened less than " + duration + " ago" );
            }
        }
        return updateNeeded;
    }
    
    public void doUpdate(VersionInfo info) {
        submit( () -> 
        {
            artifactLocks.doWhileLocked( info.artifact, () -> 
            {
                // check again that the update is still needed after we've acquired the lock.
                // Something might've already updated the artifact while we were waiting.
                final Optional<VersionInfo> existing = storage.getVersionInfo( info.artifact );
                if ( existing.map( x -> requiresUpdate(x,x.artifact) ).orElse( false ) )
                {
                    LOG.debug("doUpdate(): Refreshing "+info.artifact);
                    synchronized ( statistics ) {
                        statistics.scheduledUpdates.update();
                    }
                    try
                    {
                        provider.update(info, false );
                    }
                    finally
                    {
                        // make sure to store any changes,
                        // lastFailure will be updated if
                        // we failed to retrieve the version info
                        storage.saveOrUpdate(info);
                    }
                } else {
                    LOG.debug("doUpdate(): Doing nothing, concurrent update to "+info.artifact+" already updated it");
                }                
            }); 
        });
    }
    
    private void submit(ThrowingRunnable job) 
    {
        threadPool.submit( () -> 
        {
            try 
            {
                job.run();
            } catch(Exception e) {
                LOG.error("submit(): Caught "+e.getMessage(),e);
            }
        });
    }
    
    @Override
    public void startThread() 
    {
        synchronized( THREAD_LOCK ) 
        {
            if ( ! shutdown ) 
            {
                thread = new BGThread();
                thread.start();
            }
        }
    }
    
    @Override
    public void close() throws IOException
    {
        shutdown = true;
        synchronized( THREAD_LOCK ) 
        {
            if ( thread != null ) 
            {
                try {
                    thread.shutdown();
                }
                catch ( InterruptedException e ) {
                    throw new InterruptedIOException( e.getMessage() );
                }
                finally {
                    thread = null;
                }
            }
        }
        threadPool.shutdownNow();
    }

    public void setConfiguration(Configuration configuration)
    {
        Validate.notNull( configuration, " must not be null" );
        this.configuration = configuration;
    }

    @Override
    public Statistics getStatistics() {
        synchronized ( statistics ) {
            return statistics.createCopy();
        }
    }

    @Override
    public void resetStatistics()
    {
        synchronized ( statistics ) {
            statistics.reset();
        }
    }
}