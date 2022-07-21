package de.codesourcery.versiontracker.enforcerrule;

import de.codesourcery.versiontracker.client.IAPIClient;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.Blacklist;
import de.codesourcery.versiontracker.common.Version;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.isNull;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertNull;

class DependencyAgeRuleTest {

    @Test
    void testMaxAgeExceeded() throws ExpressionEvaluationException, IOException {

        // mock data
        final ZonedDateTime currentTime = date( "2022-08-01 11:12:13" );

        final Artifact dep1 = new Artifact("de.codesourcery","test","1.0.0","","jar");
        final List<Artifact> projectDependencies = List.of( dep1 );

        final ArtifactResponse resp1 = new ArtifactResponse();
        resp1.artifact = dep1;
        resp1.currentVersion = new Version( dep1.version , date("2022-07-10 11:12:13"));
        resp1.latestVersion = new Version( "1.0.1", date( "2022-07-20 11:12:13" ) );
        resp1.updateAvailable = ArtifactResponse.UpdateAvailable.YES;
        final List<ArtifactResponse> apiResponse = List.of( resp1 );

        // setup
        final EnforcerRuleHelper helper = createEnforcerRuleHelper( projectDependencies );
        final IAPIClient apiClient = createApiClient( projectDependencies, apiResponse );
        final DependencyAgeRule rule = createRule( projectDependencies, apiResponse, currentTime, apiClient);

        // rule.apiEndpoint = "http://mock";
        rule.maxAge = "1w";
        rule.warnAge = "3d";

        // run test
        assertThatThrownBy( () -> rule.execute( helper ) ).isInstanceOf( EnforcerRuleException.class ).hasMessageContaining( "One or more dependencies of this project are older than the allowed maximum age (1 week)" );

        // verify
        verify( helper, apiClient );
    }

    private DependencyAgeRule createRule(List<Artifact> projectDependencies, List<ArtifactResponse> apiResponse,ZonedDateTime currentTime, IAPIClient apiClient) throws IOException
    {
        // setup
        return new DependencyAgeRule() {
            @Override
            protected IAPIClient getLocalAPIClient(boolean debug) {
                return apiClient;
            }

            @Override
            protected ZonedDateTime currentTime() {
                return currentTime;
            }
        };
    }

    private IAPIClient createApiClient(List<Artifact> projectDependencies, List<ArtifactResponse> apiResponse) throws IOException
    {
        final IAPIClient apiClient = createMock( IAPIClient.class );
        expect( apiClient.query( isA( List.class ), isNull() ) ).andAnswer( (IAnswer<List<ArtifactResponse>>) () ->
        {
            final List<Artifact> artifacts = EasyMock.getCurrentArgument( 0 );
            assertThat( artifacts ).containsAll( projectDependencies );

            final Blacklist blacklist = EasyMock.getCurrentArgument( 1 );
            assertNull( blacklist );
            return apiResponse;
        });
        replay( apiClient );
        return apiClient;
    }

    private EnforcerRuleHelper createEnforcerRuleHelper(List<Artifact> projectDependencies) throws ExpressionEvaluationException {

        final Log log = EasyMock.createNiceMock( Log.class );
        replay( log );

        final EnforcerRuleHelper helper = createMock( EnforcerRuleHelper.class );
        expect( helper.getLog() ).andReturn( log ).anyTimes();
        expect( helper.evaluate( "${project}" ) ).andReturn( createMavenProject( projectDependencies ) );
        replay( helper );
        return helper;
    }

    private static ZonedDateTime date(String s)
    {
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss" ).withZone( ZoneId.of( "UTC" ) );
        return ZonedDateTime.parse( s, formatter );
    }

    private static MavenProject createMavenProject(List<Artifact> dependencies)
    {
        return createMavenProject( dependencies.iterator() );
    }

    private static MavenProject createMavenProject(Iterator<Artifact> it)
    {
        final MavenProject mavenProject = new MavenProject();

        final org.apache.maven.artifact.Artifact mavenArtifact =
            EasyMock.createMock( org.apache.maven.artifact.Artifact.class );
        replay( mavenArtifact );
        mavenProject.setArtifact( mavenArtifact );

        final Set<org.apache.maven.artifact.Artifact> deps = new HashSet<>();
        while ( it.hasNext() ) {
            final Artifact art = it.next();
            // need to map NULL classifier to empty string as otherwise
            // org.apache.maven.artifact.Artifact will try to call the ArtifactHandler
            // which is NULL and hence crash with a NPE
            final String classifier = art.getClassifier() == null ? "" : art.getClassifier();
            org.apache.maven.artifact.Artifact mvnArtifact = new DefaultArtifact( art.groupId,
                art.artifactId,art.version,"compile","jar",classifier,null);
            deps.add( mvnArtifact );
        }
        mavenProject.setDependencyArtifacts( deps );
        return mavenProject;
    }
}