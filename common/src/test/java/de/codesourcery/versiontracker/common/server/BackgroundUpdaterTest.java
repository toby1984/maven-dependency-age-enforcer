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
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingConsumer;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class BackgroundUpdaterTest
{
    protected static abstract class MockProvider implements IVersionProvider {
        private final Statistics s = new Statistics();

        @Override
        public Statistics getStatistics()
        {
            synchronized( s )
            {
                return s.createCopy();
            }
        }

        @Override
        public void resetStatistics()
        {
            synchronized( s )
            {
                s.reset();
            }
        }

        @Override
        public void setConfigurationProvider(ConfigurationProvider configuration)
        {
        }
    }

    @Test
    void testBackgroundUpdateHappyPath() throws InterruptedException, IOException
    {
        final Version newVersion = new Version( "1.0.0", ZonedDateTime.now() );

        final IVersionStorage storage = new InMemoryVersionStorage();

        final VersionInfo original = new VersionInfo();
        original.artifact = new Artifact( "org.apache.commons","commons-lang3", "3.2.0",null,"jar" );

        storage.saveOrUpdate( original );

        final AtomicReference<ZonedDateTime> firstUpdateAttempt = new AtomicReference<>();

        final SharedLockCache lockCache = new SharedLockCache();
        final IVersionProvider iVersionProvider = new MockProvider() {

            @Override
            public UpdateResult update(VersionInfo info, boolean force)
            {
                final ZonedDateTime now = ZonedDateTime.now();

                // remember only the very first attempt , verified by unit test
                firstUpdateAttempt.compareAndSet( null, now );

                info.versions.clear();
                info.versions.add( newVersion );
                info.lastSuccessDate = now;
                info.lastFailureDate = null;
                info.latestReleaseVersion = newVersion.copy();
                info.latestSnapshotVersion = null;
                info.nextBackgroundUpdate = null;
                info.creationDate = now;
                return UpdateResult.UPDATED;
            }
        };

        BackgroundUpdater updater = null;
        final Configuration config = new Configuration();
        config.setBgUpdateCheckInterval( Duration.ofMillis( 100 ) );

        try ( ConfigurationProvider configProvider = new ConfigurationProvider() {
            public synchronized Configuration getConfiguration() {
                return config;
            } } )
        {
            final int nextUpdateDelayMillis = 500;
            final ZonedDateTime nextUpdateTs = ZonedDateTime.now().plus( Duration.ofMillis( nextUpdateDelayMillis ) );
            updater = new BackgroundUpdater( storage, iVersionProvider, lockCache ) {
                @Override
                protected ZonedDateTime calculateNextUpdateTimestamp(ZonedDateTime now)
                {
                    return nextUpdateTs;
                }
            };
            updater.setConfigurationProvider(configProvider);

            final ZonedDateTime start = ZonedDateTime.now();
            updater.startThread();
            Thread.sleep(2000);

            final List<VersionInfo> versions = storage.getAllVersions();

            final ZonedDateTime ts = firstUpdateAttempt.get();
            assertNotNull( ts, "update() never called?" );
            final long actualDelayMillis = Duration.between( start, ts ).toMillis();
            assertTrue(
                actualDelayMillis >= nextUpdateDelayMillis-500 && actualDelayMillis <= nextUpdateDelayMillis+500,
                "delay out of range, expected around "+nextUpdateDelayMillis+" but was "+actualDelayMillis);

            assertEquals( 1, versions.size() );
            final VersionInfo actual = versions.getFirst();
            assertNotSame( original, actual );
            assertEquals( original.artifact, actual.artifact );
            assertNull( original.lastSuccessDate );
            assertNotNull( actual.lastSuccessDate );
            assertEquals( 1, actual.versions.size() );
        } finally {
            if ( updater != null ) {
                updater.close();
            }
        }
    }

    /**
     * A stale artifact that has no background update scheduled yet must only get
     * an update scheduled (within the configured delay window), the actual update
     * must NOT be performed right away.
     */
    @Test
    void testStaleArtifactWithoutScheduleOnlyGetsScheduled() throws Throwable
    {
        final IVersionStorage storage = new InMemoryVersionStorage();
        final VersionInfo info = staleInfo( null );
        storage.saveOrUpdate( info );

        final AtomicInteger updateCount = new AtomicInteger();
        final IVersionProvider provider = new MockProvider() {
            @Override
            public UpdateResult update(VersionInfo toUpdate, boolean force)
            {
                updateCount.incrementAndGet();
                return UpdateResult.UPDATED;
            }
        };

        final Configuration config = newConfig();
        config.setBackgroundUpdateDelayWindow( new Configuration.DurationRange( Duration.ofMinutes( 10 ), Duration.ofMinutes( 120 ) ) );

        final ZonedDateTime before = ZonedDateTime.now();
        withUpdater( config, storage, provider, updater ->
        {
            updater.doUpdate( info );
            final VersionInfo stored = awaitStored( storage, x -> x.nextBackgroundUpdate != null );
            assertEquals( 0, updateCount.get() );
            assertFalse( stored.nextBackgroundUpdate.isBefore( before.plusMinutes( 10 ) ), "scheduled too early: " + stored.nextBackgroundUpdate );
            assertFalse( stored.nextBackgroundUpdate.isAfter( ZonedDateTime.now().plusMinutes( 120 ) ), "scheduled too late: " + stored.nextBackgroundUpdate );
        } );
    }

    /**
     * A stale artifact whose scheduled background update is not due yet must not be updated.
     */
    @Test
    void testScheduledButNotDueArtifactIsNotUpdated() throws Throwable
    {
        final IVersionStorage storage = new InMemoryVersionStorage();
        final ZonedDateTime due = ZonedDateTime.now().plusHours( 1 );
        final VersionInfo info = staleInfo( due );
        storage.saveOrUpdate( info );

        final AtomicInteger updateCount = new AtomicInteger();
        final IVersionProvider provider = new MockProvider() {
            @Override
            public UpdateResult update(VersionInfo toUpdate, boolean force)
            {
                updateCount.incrementAndGet();
                return UpdateResult.UPDATED;
            }
        };

        withUpdater( newConfig(), storage, provider, updater ->
        {
            updater.doUpdate( info );
            Thread.sleep( 1000 );
            assertEquals( 0, updateCount.get() );
            final VersionInfo stored = storage.getAllVersions().getFirst();
            assertEquals( due.toInstant(), stored.nextBackgroundUpdate.toInstant() );
        } );
    }

    /**
     * A stale artifact whose scheduled background update is due must be updated
     * and (on success) the schedule must be cleared.
     */
    @Test
    void testDueArtifactIsUpdated() throws Throwable
    {
        final IVersionStorage storage = new InMemoryVersionStorage();
        final VersionInfo info = staleInfo( ZonedDateTime.now().minusMinutes( 5 ) );
        storage.saveOrUpdate( info );

        final AtomicInteger updateCount = new AtomicInteger();
        final IVersionProvider provider = new MockProvider() {
            @Override
            public UpdateResult update(VersionInfo toUpdate, boolean force)
            {
                updateCount.incrementAndGet();
                toUpdate.lastSuccessDate = ZonedDateTime.now();
                // the real IVersionProvider clears the schedule on a successful update
                toUpdate.nextBackgroundUpdate = null;
                return UpdateResult.UPDATED;
            }
        };

        final ZonedDateTime before = ZonedDateTime.now();
        withUpdater( newConfig(), storage, provider, updater ->
        {
            updater.doUpdate( info );
            final VersionInfo stored = awaitStored( storage, x -> x.nextBackgroundUpdate == null );
            assertEquals( 1, updateCount.get() );
            assertNotNull( stored.lastSuccessDate );
            assertFalse( stored.lastSuccessDate.isBefore( before ) );
        } );
    }

    /**
     * If a due background update fails, the schedule must be kept so the
     * artifact gets retried once it is considered stale again.
     */
    @Test
    void testFailedUpdateKeepsSchedule() throws Throwable
    {
        final IVersionStorage storage = new InMemoryVersionStorage();
        final ZonedDateTime due = ZonedDateTime.now().minusMinutes( 5 );
        final VersionInfo info = staleInfo( due );
        storage.saveOrUpdate( info );

        final AtomicInteger updateCount = new AtomicInteger();
        final IVersionProvider provider = new MockProvider() {
            @Override
            public UpdateResult update(VersionInfo toUpdate, boolean force) throws IOException
            {
                updateCount.incrementAndGet();
                // the real IVersionProvider sets lastFailureDate before re-throwing
                toUpdate.lastFailureDate = ZonedDateTime.now();
                throw new IOException( "simulated failure" );
            }
        };

        withUpdater( newConfig(), storage, provider, updater ->
        {
            updater.doUpdate( info );
            final VersionInfo stored = awaitStored( storage, x -> x.lastFailureDate != null );
            assertEquals( 1, updateCount.get() );
            assertNotNull( stored.nextBackgroundUpdate );
            assertEquals( due.toInstant(), stored.nextBackgroundUpdate.toInstant() );
        } );
    }

    @Test
    void testNextUpdateTimestampStaysWithinConfiguredWindow() throws Throwable
    {
        final Configuration config = newConfig();
        config.setBackgroundUpdateDelayWindow( new Configuration.DurationRange( Duration.ofMinutes( 10 ), Duration.ofMinutes( 120 ) ) );

        final IVersionProvider provider = new MockProvider() {
            @Override
            public UpdateResult update(VersionInfo toUpdate, boolean force)
            {
                throw new UnsupportedOperationException( "update() should not have been called" );
            }
        };

        withUpdater( config, new InMemoryVersionStorage(), provider, updater ->
        {
            final ZonedDateTime now = ZonedDateTime.now();
            for ( int i = 0 ; i < 1000 ; i++ )
            {
                final ZonedDateTime ts = updater.calculateNextUpdateTimestamp( now );
                assertFalse( ts.isBefore( now.plusMinutes( 10 ) ), "scheduled too early: " + ts );
                assertFalse( ts.isAfter( now.plusMinutes( 120 ) ), "scheduled too late: " + ts );
            }
        } );
    }

    private static Configuration newConfig()
    {
        final Configuration config = new Configuration();
        config.setMinUpdateDelayAfterSuccess( Duration.ofDays( 1 ) );
        config.setMinUpdateDelayAfterFailure( Duration.ofDays( 1 ) );
        return config;
    }

    /**
     * @return a {@link VersionInfo} that is considered stale by {@link #newConfig()}
     */
    private static VersionInfo staleInfo(ZonedDateTime nextBackgroundUpdate)
    {
        final VersionInfo info = new VersionInfo();
        info.artifact = new Artifact( "org.apache.commons", "commons-lang3", "3.2.0", null, "jar" );
        info.versions.add( new Version( "3.2.0", ZonedDateTime.now().minusYears( 1 ) ) );
        info.lastSuccessDate = ZonedDateTime.now().minusDays( 3 );
        info.nextBackgroundUpdate = nextBackgroundUpdate;
        return info;
    }

    private static void withUpdater(Configuration config, IVersionStorage storage, IVersionProvider provider,
        ThrowingConsumer<BackgroundUpdater> testBody) throws Throwable
    {
        try ( ConfigurationProvider configProvider = new ConfigurationProvider() {
            public synchronized Configuration getConfiguration() {
                return config;
            } } )
        {
            final BackgroundUpdater updater = new BackgroundUpdater( storage, provider, new SharedLockCache() );
            updater.setConfigurationProvider( configProvider );
            try {
                testBody.accept( updater );
            } finally {
                updater.close();
            }
        }
    }

    private static VersionInfo awaitStored(IVersionStorage storage, Predicate<VersionInfo> condition) throws Exception
    {
        final long deadline = System.currentTimeMillis() + 5000;
        VersionInfo lastSeen = null;
        while ( System.currentTimeMillis() < deadline )
        {
            final List<VersionInfo> all = storage.getAllVersions();
            if ( ! all.isEmpty() )
            {
                lastSeen = all.getFirst();
                if ( condition.test( lastSeen ) ) {
                    return lastSeen;
                }
            }
            Thread.sleep( 50 );
        }
        return fail( "Timed out waiting for expected storage state, last seen: " + lastSeen );
    }
}