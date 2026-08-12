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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}