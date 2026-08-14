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
package de.codesourcery.versiontracker.server;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.VersionInfo;
import de.codesourcery.versiontracker.common.server.Configuration;
import de.codesourcery.versiontracker.common.server.ConfigurationProvider;
import de.codesourcery.versiontracker.common.server.SharedLockCache;
import de.codesourcery.versiontracker.common.server.VersionTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import de.codesourcery.versiontracker.common.server.Configuration.ClientRequestArtifactUpdateMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class VersionTrackerTest
{
    private MockStorage storage;
    private VersionTracker tracker;
    
    private static final class MockStorage implements IVersionStorage
    {
        public final List<VersionInfo> stored = new ArrayList<>();
        
        @Override
        public synchronized List<VersionInfo> getAllVersions()
        {
            return stored.stream().map( VersionInfo::copy ).collect( Collectors.toCollection( ArrayList::new ) );
        }

        @Override
        public StorageStatistics getStatistics() {
            return new StorageStatistics();
        }

        @Override
        public void resetStatistics()
        {
        }

        @Override
        public synchronized void saveOrUpdate(VersionInfo info)
        {
            if ( ! stored.contains( info ) ) {
                stored.add( info.copy() );
            }
        }

        @Override
        public synchronized Optional<VersionInfo> getVersionInfo(Artifact artifact)
        {
            return stored.stream().filter( x -> x.artifact.matchesExcludingVersion( artifact ) ).findFirst();
        }

        @Override
        public synchronized void saveOrUpdate(List<VersionInfo> data)
        {
            this.stored.clear();
            this.stored.addAll( data.stream().map( VersionInfo::copy ).toList() );
        }

        @Override
        public void close()
        {
        }
    }
    
    @BeforeEach
    public void setup() {
        this.storage = new MockStorage();
    }
    
    @AfterEach
    public void tearDown() {
        if ( tracker != null ) 
        {
            try {
                tracker.close();
            } finally {
                tracker = null;
            }
        }
    }
    
    @Test
    public void test() throws InterruptedException {

        final IVersionProvider provider = new IVersionProvider() {

            private final Statistics stats = new Statistics();

            @Override
            public void setConfigurationProvider(ConfigurationProvider configuration)
            {
            }

            @Override
            public Statistics getStatistics() {
                return stats;
            }

            @Override
            public void resetStatistics()
            {
                stats.reset();
            }

            @Override
            public UpdateResult update(VersionInfo info, boolean force) {
                info.lastFailureDate = ZonedDateTime.now();
                info.nextBackgroundUpdate = null;
                return IVersionProvider.UpdateResult.BLACKLISTED;
            }
        };

        Configuration config = new Configuration();
        final SharedLockCache locks = new SharedLockCache();
        tracker = new VersionTracker(storage,provider,locks, config );
        
        final Artifact artifact = new Artifact();
        artifact.groupId="de.codesourcery";
        artifact.artifactId="versiontracker";
        artifact.version="1.0";
        for ( int i = 0 ; i < 2 ; i++ )
        {
            Map<Artifact, VersionInfo> result = tracker.getVersionInfo( Collections.singletonList( artifact ), (info, art) -> false );
            Thread.sleep(1000);
            System.out.println( result );
        }
    }

    private static final class CountingProvider implements IVersionProvider
    {
        public final AtomicInteger updateCount = new AtomicInteger();
        private final Statistics stats = new Statistics();

        @Override
        public void setConfigurationProvider(ConfigurationProvider configuration)
        {
        }

        @Override
        public Statistics getStatistics() {
            return stats;
        }

        @Override
        public void resetStatistics()
        {
            stats.reset();
        }

        @Override
        public UpdateResult update(VersionInfo info, boolean force)
        {
            updateCount.incrementAndGet();
            info.lastSuccessDate = ZonedDateTime.now();
            info.lastFailureDate = null;
            return UpdateResult.UPDATED;
        }
    }

    /**
     * Requesting an unknown artifact must always trigger a synchronous update,
     * regardless of the configured update mode and the update predicate.
     */
    @Test
    public void unknownArtifactIsAlwaysUpdatedSynchronously() throws InterruptedException
    {
        for ( ClientRequestArtifactUpdateMode mode : ClientRequestArtifactUpdateMode.values() )
        {
            this.storage = new MockStorage();
            final CountingProvider provider = new CountingProvider();
            final Artifact artifact = artifact();

            final Map<Artifact, VersionInfo> result = getVersionInfo( mode, provider, artifact, false );

            assertEquals( 1, provider.updateCount.get(), "provider not called for unknown artifact in mode " + mode );
            assertNotNull( result.get( artifact ) );
            assertNotNull( result.get( artifact ).lastSuccessDate );
        }
    }

    @Test
    public void syncModeUpdatesArtifactThatNeedsUpdate() throws InterruptedException
    {
        final Artifact artifact = artifact();
        storeInfo( artifact, ZonedDateTime.now().minusDays( 30 ), null );
        final CountingProvider provider = new CountingProvider();

        final Map<Artifact, VersionInfo> result = getVersionInfo( ClientRequestArtifactUpdateMode.SYNC, provider, artifact, true );

        assertEquals( 1, provider.updateCount.get() );
        assertNotNull( result.get( artifact ) );
        assertNotNull( result.get( artifact ).lastSuccessDate );
    }

    @Test
    public void syncModeReturnsStoredDataForArtifactThatNeedsNoUpdate() throws InterruptedException
    {
        final Artifact artifact = artifact();
        final ZonedDateTime lastSuccess = ZonedDateTime.now().minusDays( 30 );
        storeInfo( artifact, lastSuccess, null );
        final CountingProvider provider = new CountingProvider();

        final Map<Artifact, VersionInfo> result = getVersionInfo( ClientRequestArtifactUpdateMode.SYNC, provider, artifact, false );

        assertEquals( 0, provider.updateCount.get() );
        assertEquals( lastSuccess.toInstant(), result.get( artifact ).lastSuccessDate.toInstant() );
    }

    /**
     * In ASYNC mode a known artifact must never be updated while handling the
     * client request, not even when the last update failed.
     */
    @Test
    public void asyncModeNeverUpdatesKnownArtifact() throws InterruptedException
    {
        final Artifact artifact = artifact();
        final ZonedDateTime lastSuccess = ZonedDateTime.now().minusDays( 30 );
        storeInfo( artifact, lastSuccess, ZonedDateTime.now().minusDays( 1 ) );
        final CountingProvider provider = new CountingProvider();

        final Map<Artifact, VersionInfo> result = getVersionInfo( ClientRequestArtifactUpdateMode.ASYNC, provider, artifact, true );

        assertEquals( 0, provider.updateCount.get() );
        assertEquals( lastSuccess.toInstant(), result.get( artifact ).lastSuccessDate.toInstant() );
        assertNotNull( storage.stored.get( 0 ).lastRequestDate );
    }

    @Test
    public void asyncOnlyWhenSuccessfulReturnsStaleDataWhenLastUpdateSucceeded() throws InterruptedException
    {
        final Artifact artifact = artifact();
        final ZonedDateTime lastSuccess = ZonedDateTime.now().minusDays( 30 );
        storeInfo( artifact, lastSuccess, null );
        final CountingProvider provider = new CountingProvider();

        final Map<Artifact, VersionInfo> result =
            getVersionInfo( ClientRequestArtifactUpdateMode.ASYNC_ONLY_WHEN_SUCCESSFUL, provider, artifact, true );

        assertEquals( 0, provider.updateCount.get() );
        assertEquals( lastSuccess.toInstant(), result.get( artifact ).lastSuccessDate.toInstant() );
    }

    @Test
    public void asyncOnlyWhenSuccessfulUpdatesWhenLastUpdateFailed() throws InterruptedException
    {
        final Artifact artifact = artifact();
        storeInfo( artifact, ZonedDateTime.now().minusDays( 30 ), ZonedDateTime.now().minusDays( 1 ) );
        final CountingProvider provider = new CountingProvider();

        final Map<Artifact, VersionInfo> result =
            getVersionInfo( ClientRequestArtifactUpdateMode.ASYNC_ONLY_WHEN_SUCCESSFUL, provider, artifact, true );

        assertEquals( 1, provider.updateCount.get() );
        assertNotNull( result.get( artifact ) );
    }

    @Test
    public void asyncOnlyWhenSuccessfulHonoursUpdatePredicateWhenLastUpdateFailed() throws InterruptedException
    {
        final Artifact artifact = artifact();
        storeInfo( artifact, ZonedDateTime.now().minusDays( 30 ), ZonedDateTime.now().minusDays( 1 ) );
        final CountingProvider provider = new CountingProvider();

        getVersionInfo( ClientRequestArtifactUpdateMode.ASYNC_ONLY_WHEN_SUCCESSFUL, provider, artifact, false );

        assertEquals( 0, provider.updateCount.get() );
    }

    private static Artifact artifact()
    {
        final Artifact artifact = new Artifact();
        artifact.groupId = "de.codesourcery";
        artifact.artifactId = "versiontracker";
        artifact.version = "1.0";
        return artifact;
    }

    private void storeInfo(Artifact artifact, ZonedDateTime lastSuccessDate, ZonedDateTime lastFailureDate)
    {
        final VersionInfo info = new VersionInfo();
        info.artifact = artifact;
        info.lastSuccessDate = lastSuccessDate;
        info.lastFailureDate = lastFailureDate;
        storage.saveOrUpdate( info );
    }

    private Map<Artifact, VersionInfo> getVersionInfo(ClientRequestArtifactUpdateMode mode,
        IVersionProvider provider, Artifact artifact, boolean artifactNeedsUpdate) throws InterruptedException
    {
        final Configuration config = new Configuration();
        config.setClientArtifactUpdateMode( mode );
        if ( tracker != null ) {
            tracker.close();
        }
        tracker = new VersionTracker( storage, provider, new SharedLockCache(), config );
        return tracker.getVersionInfo( List.of( artifact ), (info, art) -> artifactNeedsUpdate );
    }
}
