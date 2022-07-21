package de.codesourcery.versiontracker.common.server;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.QueryRequest;
import de.codesourcery.versiontracker.common.QueryResponse;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;
import org.easymock.IAnswer;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easymock.EasyMock.anyInt;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.getCurrentArgument;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class APIImplTest
{
    @Test
    public void testDataUnavailableFromStorage() throws Exception
    {
        final Version latestRelease = new Version( "1.0.1", date( "2022-07-05 11:12:13" ) );
        final Version latestSnapshot = new Version( "1.0.2-SNAPSHOT", date( "2022-07-15 11:12:13" ) );

        final Artifact art1 = new Artifact();
        art1.groupId = "de.codesourcery";
        art1.artifactId = "version-tracker";
        art1.version = "1.0.0";

        // =============
        // setup mocks
        // =============

        // mock BG updater
        final IBackgroundUpdater bgUpdater = createMock( IBackgroundUpdater.class );
        bgUpdater.close();
        replay( bgUpdater );

        // mock version provider
        final IVersionProvider versionProvider = createMock( IVersionProvider.class );
        expect( versionProvider.update( isA( VersionInfo.class ), isA( Set.class ) ) ).andAnswer( () -> {

            final VersionInfo versionInfo = getCurrentArgument( 0 );
            final Set<String> additionalVersionsToFetchReleaseDatesFor = getCurrentArgument( 1 );
            assertThat( additionalVersionsToFetchReleaseDatesFor ).containsOnly( "1.0.0" );

            assertThat( versionInfo.artifact.artifactId ).isEqualTo( art1.artifactId );
            assertThat( versionInfo.artifact.groupId ).isEqualTo( art1.groupId );
            assertThat( versionInfo.artifact.version ).isEqualTo( art1.version);
            assertThat( versionInfo.versions ).isEmpty();

            versionInfo.versions.add( new Version( "1.0.0", date( "2022-07-01 11:12:13" ) ) );
            versionInfo.versions.add( latestRelease );
            versionInfo.versions.add( latestSnapshot );

            versionInfo.latestReleaseVersion = latestRelease;
            versionInfo.latestSnapshotVersion = latestSnapshot;

            return IVersionProvider.UpdateResult.UPDATED;
        });
        replay( versionProvider );

        // mock version storage
        final IVersionStorage versionStorage = createMock( IVersionStorage.class );
        expect( versionStorage.getVersionInfo( isA(Artifact.class) ) ).andAnswer( () -> {
            final Artifact a = getCurrentArgument( 0 );
            assertThat( a.artifactId ).isEqualTo( art1.artifactId );
            assertThat( a.groupId ).isEqualTo( art1.groupId);
            return Optional.empty();
        });
        versionStorage.saveOrUpdate( isA(VersionInfo.class) );
        expectLastCall().andAnswer( () -> {
            final VersionInfo versionInfo = getCurrentArgument( 0 );
            assertThat( versionInfo.artifact.artifactId ).isEqualTo( art1.artifactId );
            assertThat( versionInfo.artifact.groupId ).isEqualTo( art1.groupId );
            assertThat( versionInfo.artifact.version ).isEqualTo( art1.version);
            assertThat( versionInfo.versions ).hasSize( 3 );
            assertThat( versionInfo.versions ).map( x -> x.versionString ).containsOnly( latestSnapshot.versionString, latestRelease.versionString, "1.0.0" );
            return null;
        });
        versionStorage.close();
        replay( versionStorage );

        try ( APIImpl api = new APIImpl( APIImpl.Mode.CLIENT ) {
            @Override
            protected IBackgroundUpdater createBackgroundUpdater(SharedLockCache lockCache) {
                return bgUpdater;
            }

            @Override
            protected IVersionProvider createVersionProvider() {
                return versionProvider;
            }

            @Override
            protected IVersionStorage createVersionStorage() {
                return versionStorage;
            }

            @Override
            protected IVersionTracker createVersionTracker(SharedLockCache lockCache) {
                return new VersionTracker( versionStorage, createVersionProvider(), lockCache );
            }
        } ) {
            api.setRegisterShutdownHook( false );
            api.init( false, false );

            // actual test execution
            final QueryRequest query = new QueryRequest();
            query.artifacts = List.of( art1 );

            final QueryResponse response = api.processQuery( query );

            // verify assertions
            assertThat( response.artifacts ).hasSize( 1 );
            final ArtifactResponse resp1 = response.artifacts.get( 0 );
            resp1.updateAvailable = ArtifactResponse.UpdateAvailable.YES;
            assertThat( resp1.artifact ).isEqualTo( art1 );
            assertThat( resp1.latestVersion ).isEqualTo( latestRelease );
            assertThat( resp1.currentVersion.versionString ).isEqualTo( art1.version );
        }
        verify( versionStorage, versionProvider, bgUpdater );
    }

    @Test
    public void testDataAvailableFromStorage() throws Exception {

        final Artifact art1 = new Artifact();
        art1.groupId = "de.codesourcery";
        art1.artifactId = "version-tracker";
        art1.version = "1.0.0";

        // =============
        // setup mocks
        // =============

        // mock BG updater
        final IBackgroundUpdater bgUpdater = createMock( IBackgroundUpdater.class );
        expect( bgUpdater.requiresUpdate( isA(Optional.class) ) ).andAnswer( () ->
        {
            Optional<VersionInfo> arg = getCurrentArgument( 0 );
            assertThat( arg ).isPresent();
            assertThat( arg.get().artifact.groupId ).isEqualTo( art1.groupId );
            assertThat( arg.get().artifact.artifactId ).isEqualTo( art1.artifactId );
            return false;
        });
        bgUpdater.close();
        replay( bgUpdater );

        // mock version provider
        final IVersionProvider versionProvider = createMock( IVersionProvider.class );
        replay( versionProvider );

        // mock version storage
        final VersionInfo versionInfo = new VersionInfo();
        versionInfo.artifact = art1;
        versionInfo.versions = new ArrayList<>();
        versionInfo.versions.add( new Version( "1.0.0", date( "2022-07-01 11:12:13" ) ) );
        final Version latestRelease = new Version( "1.0.1", date( "2022-07-05 11:12:13" ) );
        final Version latestSnapshot = new Version( "1.0.2-SNAPSHOT", date( "2022-07-15 11:12:13" ) );
        versionInfo.versions.add( latestRelease );
        versionInfo.versions.add( latestSnapshot );
        versionInfo.latestReleaseVersion = latestRelease;
        versionInfo.latestSnapshotVersion = latestSnapshot;

        final IVersionStorage versionStorage = createMock( IVersionStorage.class );
        expect( versionStorage.getVersionInfo( isA(Artifact.class) ) ).andAnswer( () -> {
            final Artifact a = getCurrentArgument( 0 );
            assertThat( a.groupId ).isEqualTo( art1.groupId );
            assertThat( a.artifactId ).isEqualTo( art1.artifactId);
            return Optional.of( versionInfo );
        });
        versionStorage.updateLastRequestDate( isA(Artifact.class), isA(ZonedDateTime.class) );
        expectLastCall().andAnswer( () -> {
            final Artifact a = getCurrentArgument( 0 );
            assertThat( a.groupId ).isEqualTo( art1.groupId );
            assertThat( a.artifactId ).isEqualTo( art1.artifactId );
            final ZonedDateTime ts = getCurrentArgument( 1 );
            assertNotNull( ts );
            return null;
        });
        versionStorage.close();
        replay( versionStorage );

        // mock version tracker
        try ( APIImpl api = new APIImpl( APIImpl.Mode.CLIENT )
        {
            @Override
            protected IBackgroundUpdater createBackgroundUpdater(SharedLockCache lockCache) {
                return bgUpdater;
            }

            @Override
            protected IVersionProvider createVersionProvider() {
                return versionProvider;
            }

            @Override
            protected IVersionStorage createVersionStorage() {
                return versionStorage;
            }

            @Override
            protected IVersionTracker createVersionTracker(SharedLockCache lockCache) {
                return new VersionTracker( versionStorage, createVersionProvider(), lockCache );
            }
        } ) {
            api.setRegisterShutdownHook( false );
            api.init( false, false );

            // actual test execution
            final QueryRequest query = new QueryRequest();
            query.artifacts = List.of( art1 );

            final QueryResponse response = api.processQuery( query );

            // verify assertions
            assertThat( response.artifacts ).hasSize( 1 );
            final ArtifactResponse resp1 = response.artifacts.get( 0 );
            resp1.updateAvailable = ArtifactResponse.UpdateAvailable.YES;
            assertThat( resp1.artifact ).isEqualTo( art1 );
            assertThat( resp1.latestVersion ).isEqualTo( latestRelease );
            assertThat( resp1.currentVersion.versionString ).isEqualTo( art1.version );
        }
        verify( versionStorage, versionProvider, bgUpdater );
    }

    private static ZonedDateTime date(String s)
    {
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss" ).withZone( ZoneId.of( "UTC" ) );
        return ZonedDateTime.parse( s, formatter );
    }
}