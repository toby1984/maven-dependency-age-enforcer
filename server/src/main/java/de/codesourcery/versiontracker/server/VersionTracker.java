/**
 * Copyright 2015 Tobias Gierke <tobias.gierke@code-sourcery.de>
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
package de.codesourcery.versiontracker.server;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactMap;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionProvider.UpdateResult;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.VersionInfo;
import de.codesourcery.versiontracker.server.APIServlet.IUpdateCallback;
import de.codesourcery.versiontracker.server.VersionTracker.PollingThread;

/**
 * This class is responsible for requesting and periodically refreshing artifact metadata as well
 * as providing this metadata to clients using the {@link #getVersionInfo(Artifact)} method. 
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class VersionTracker implements AutoCloseable
{
    private static final Logger LOG = LogManager.getLogger(VersionTracker.class);

    private PollingThread pollingThread;

    private final Object QUERIES_LOCK = new Object();

    // @GuardedBy( QUERIES_LOCK )
    private final ArtifactMap<Artifact> queries = new ArtifactMap<Artifact>();

    private final AtomicInteger newItemsToQuery = new AtomicInteger(0);

    private final VersionCache trackedVersions = new VersionCache();

    private Duration pollingInterval = Duration.ofHours( 24 );

    private IVersionStorage versionStorage = new FlatFileStorage( new File("/tmp/artifacts.json" ) );
    private IVersionProvider versionProvider = new MavenCentralVersionProvider();    

    private final Object THREAD_POOL_LOCK = new Object();

    // @GuardedBy( THREAD_POOL_LOCK )
    private int maxConcurrentThreads = 1;
    // @GuardedBy( THREAD_POOL_LOCK )
    private ThreadPoolExecutor threadPool;

    private final ThreadFactory threadFactory = new ThreadFactory() {

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

    private Duration refreshInterval = Duration.ofHours( 24 );

    protected final class PollingThread extends Thread
    {
        {
            setDaemon(true);
            setName("watchdog");
        }

        @Override
        public void run()
        {
            trackedVersions.clear();
            try {
                trackedVersions.addAll( versionStorage.getAllVersions() );
            } 
            catch (IOException e) {
                LOG.error("run(): Loading versions failed");
                e.printStackTrace();
            }

            LOG.info("run(): Background thread started, loaded "+trackedVersions.size()+" versions");            
            while ( true ) 
            {
                final ZonedDateTime now = ZonedDateTime.now(); 
                final List<VersionInfo> outdated = trackedVersions.getCopies( info -> info.isNewItem() ||
                        Duration.between( info.lastPolledDate(), now ).compareTo( refreshInterval ) > 0 );

                final List<Artifact> queriesCopy;
                synchronized( QUERIES_LOCK ) 
                {
                    if ( ! queries.isEmpty() )
                    {
                        if ( LOG.isDebugEnabled() ) {
                            LOG.debug("run(): Processing "+queries.size()+" pending queries");
                        }
                        queriesCopy = queries.stream().map( x -> x.copy() ).peek(x -> {
                            LOG.debug("run(): QUERY: "+x);
                        }).collect( Collectors.toList() );
                        queries.clear();
                    } else {
                        queriesCopy = Collections.emptyList();
                    }
                }

                trackedVersions.visitMatches( queriesCopy, (artifact,wasFound) -> 
                {
                    if ( wasFound == Boolean.TRUE ) {
                        LOG.debug("run(): Query already satisfied: "+artifact);
                    } else {
                        LOG.debug("run(): Scheduling new query for "+artifact);
                        VersionInfo info = new VersionInfo();
                        info.creationDate = ZonedDateTime.now();
                        info.artifact = artifact;
                        info.lastRequestDate = ZonedDateTime.now();
                        outdated.add( info );                        
                    }
                } );

                if ( ! outdated.isEmpty() )
                {
                    LOG.debug("run(): Fetching info for "+outdated.size()+" outdated/missing versions");
                    final CountDownLatch latch = new CountDownLatch(outdated.size());
                    final List<VersionInfo> itemsToAdd = new ArrayList<>();

                    for (VersionInfo someItem : outdated ) 
                    {
                        final boolean isNewItem = someItem.isNewItem();

                        final VersionInfo itemCopy = someItem.copy();
                        update(itemCopy,(updateResult,info,error)-> 
                        {
                            LOG.debug("run(): Got "+updateResult+" , error: "+error+" for "+info);
                            try 
                            {
                                switch( updateResult ) 
                                {
                                    case BLACKLISTED:
                                        synchronized( itemsToAdd ) {
                                            itemsToAdd.add( info ); // keep the old item
                                        }
                                        break;
                                    case ARTIFACT_NOT_FOUND:
                                        synchronized( itemsToAdd ) {
                                            itemsToAdd.add( info ); // keep the old item
                                        }                                    
                                        break;
                                    case ERROR:
                                        if ( LOG.isDebugEnabled() ) {
                                            LOG.debug("run(): Failed to fetch version information for "+info,error);
                                        } else {
                                            LOG.debug("run(): Failed to fetch version information for "+info+": "+error.getMessage());
                                        }                                    
                                        synchronized( itemsToAdd ) {
                                            itemsToAdd.add( info ); // keep the old item
                                        }                                    
                                        break;
                                    case NO_CHANGES_ON_SERVER:
                                        synchronized( itemsToAdd ) {
                                            itemsToAdd.add( info ); // keep the old item
                                        }                                  
                                        break;
                                    case UPDATED:
                                        LOG.debug("run(): Got info: "+info);
                                        synchronized( itemsToAdd ) {
                                            itemsToAdd.add( info );
                                        }
                                        break;
                                    default:
                                        throw new RuntimeException("Unreachable code reached");
                                }
                            } 
                            finally 
                            {
                                latch.countDown();
                                if( isNewItem ) {
                                    newItemsToQuery.decrementAndGet();
                                }
                            }
                        });
                    }

                    final long start = System.currentTimeMillis();
                    while ( true ) 
                    {
                        try {
                            LOG.debug("run(): Waiting for "+outdated.size()+" queries to finish...");
                            if ( latch.await( 1, TimeUnit.SECONDS ) ) {
                                break;
                            }
                        } catch (InterruptedException e) {
                        }
                        final float elapsedSeconds= (System.currentTimeMillis() - start)/1000.0f;
                        LOG.warn("run(): Still awaiting "+latch.getCount()+"/"+outdated.size()+" responses for "+elapsedSeconds+" seconds");
                    }

                    LOG.debug("run(): Updating cache "+outdated.size()+" to remove, "+itemsToAdd.size()+" to add");                    
                    trackedVersions.replaceAll( outdated , itemsToAdd );

                    LOG.debug("run(): Persisting changes");
                    final long persistStart = System.currentTimeMillis();
                    final List<VersionInfo> copy = trackedVersions.getAllEntries();
                    try 
                    {
                        versionStorage.saveAll( copy );
                        LOG.debug("run(): Persisting changes returned");                        
                    } 
                    catch (Exception e) 
                    {
                        LOG.error("run(): Failed to save tracked versions",e);
                    } 
                    finally {
                        final float elapsedSeconds = (System.currentTimeMillis()-persistStart)/1000f; 
                        LOG.debug("run(): Persisting "+copy.size()+" artifacts took "+elapsedSeconds+" seconds");
                    }
                }

                // sleep some time
                synchronized( QUERIES_LOCK ) 
                {
                    try 
                    {
                        if ( queries.isEmpty() ) {
                            LOG.debug("run(): Thread is going to sleep");
                            QUERIES_LOCK.wait( pollingInterval.toMillis() );
                        }
                    } catch (InterruptedException e) {
                        LOG.error("run(): Caught ",e);
                    }
                }
                LOG.debug("run(): Thread woke up.");
            }
        }

        private void update(VersionInfo info,IUpdateCallback callback) 
        {
            submit( () -> 
            {
                Exception ex = null;
                UpdateResult result = UpdateResult.ERROR;
                final String oldName = Thread.currentThread().getName();
                try {
                    Thread.currentThread().setName( oldName+" (Retrieving info for "+info.artifact+")");
                    result = versionProvider.update( info );
                } 
                catch (Exception e) {
                    ex = e;
                } finally {
                    try 
                    {
                        Thread.currentThread().setName( oldName+" (Invoking callback for "+info.artifact+")");
                        callback.received(result,info,ex);
                    } finally {
                        Thread.currentThread().setName( oldName );
                    }
                }
            });
        }
    }


    public VersionTracker() {
    }

    public VersionTracker(IVersionStorage versionStorage,IVersionProvider versionProvider) {
        this.versionProvider = versionProvider;
        this.versionStorage = versionStorage;
    }    

    public void start() {
        pollingThread = new PollingThread();
        pollingThread.start();        
    }

    /**
     * Try to retrieve version information for a given artifact.
     * 
     * @param artifact
     * @return
     */
    public Map<Artifact,VersionInfo> getVersionInfo(List<Artifact> artifacts) 
    {
        final Map<Artifact,VersionInfo> resultMap = new HashMap<>();
        final List<Artifact> missingArtifacts = new ArrayList<>();
        for ( Artifact artifact : artifacts ) 
        {
            final Optional<VersionInfo> result = trackedVersions.doWithVersion(artifact, match -> 
            {
                Optional<VersionInfo> hack = match; // hack to work around type inference bug in Eclipse
                if ( hack.isPresent() )
                {
                    final VersionInfo value = hack.get();
                    value.lastRequestDate = ZonedDateTime.now();
                    hack = Optional.of( value.copy() );
                } 
                return hack;
            });

            if ( result.isPresent() ) {
                LOG.debug("getVersionInfo(): Got "+result.get());   
                resultMap.put(artifact,result.get());
            } 
            else 
            {
                LOG.debug("getVersionInfo(): Got no metadata for "+artifact+" yet,starting to track it");
                missingArtifacts.add( artifact );
            }
        }

        if ( ! missingArtifacts.isEmpty() ) 
        {
            LOG.debug("getVersionInfo(): "+missingArtifacts.size()+" artifacts need to be queried from repository");
            synchronized( QUERIES_LOCK ) 
            {        
                for ( Artifact artifact: missingArtifacts ) {

                    if ( ! queries.contains( artifact.groupId, artifact.artifactId ) ) 
                    {
                        queries.put( artifact.groupId,artifact.artifactId, artifact.copy() );
                    }
                }
                QUERIES_LOCK.notifyAll();            
            }

            final long waitStart = System.currentTimeMillis();
            while ( ! missingArtifacts.isEmpty() ) 
            {
                for ( Artifact artifact : artifacts ) 
                {
                    final Optional<VersionInfo> result = trackedVersions.doWithVersion(artifact, match -> 
                    {
                        Optional<VersionInfo> hack = match; // hack to work around type inference bug in Eclipse
                        if ( hack.isPresent() )
                        {
                            final VersionInfo value = hack.get();
                            value.lastRequestDate = ZonedDateTime.now();
                            hack = Optional.of( value.copy() );
                        } 
                        return hack;
                    });
                    if ( result.isPresent() ) {
                        LOG.debug("getVersionInfo(): Got query result "+result.get());
                        missingArtifacts.remove( artifact );
                        resultMap.put( artifact, result.get() );
                    }
                }

                if ( ! missingArtifacts.isEmpty() ) { 
                    final float elapsed = (System.currentTimeMillis()-waitStart)/1000f;
                    LOG.debug("getVersionInfo(): Still waiting for "+missingArtifacts.size()+" results (waited "+elapsed+" seconds)");
                    try {
                        Thread.sleep( 500 );
                    } catch(Exception e) {
                        // can't help it
                    }                
                }
            }            
        }
        return resultMap;
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

    /**
     * Sets the time interval after which the main-thread will wake up and check
     * whether any repository items need to have their metadata refreshed from the 
     * source repository again.
     * 
     * @param pollingInterval
     */
    public void setRefreshInterval(Duration refreshInterval) {
        Validate.notNull(pollingInterval,"refresh interval must not be NULL");
        if ( refreshInterval.compareTo( Duration.ofSeconds( 1 ) ) < 0 ) {
            throw new IllegalArgumentException("refresh interval needs to be at least 1 second");
        }
        this.refreshInterval = refreshInterval;
    }    

    /**
     * Sets the time interval after which the main-thread will wake up and check
     * whether any repository items need to have their metadata refreshed from the 
     * source repository again.
     * 
     * @param pollingInterval
     */
    public void setPollingInterval(Duration pollingInterval) {
        Validate.notNull(pollingInterval,"pollingInterval must not be NULL");
        if ( pollingInterval.compareTo( Duration.ofSeconds( 1 ) ) < 0 ) {
            throw new IllegalArgumentException("polling interval needs to be at least 1 second");
        }
        this.pollingInterval = pollingInterval;
    }
}