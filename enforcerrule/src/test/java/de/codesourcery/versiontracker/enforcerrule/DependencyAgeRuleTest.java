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
package de.codesourcery.versiontracker.enforcerrule;

import de.codesourcery.versiontracker.client.api.IAPIClient;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.Blacklist;
import de.codesourcery.versiontracker.common.Version;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.enforcer.rule.api.EnforcerLogger;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.project.MavenProject;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
    void testMaxAgeExceeded() throws IOException, IllegalAccessException {

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
        final MavenProject mavenProject = createMavenProject( projectDependencies );
        final IAPIClient apiClient = createApiClient( projectDependencies, apiResponse );
        final DependencyAgeRule rule = createRule( currentTime, apiClient);

        // new EnforcerRule API uses @Inject to inject dependencies
        inject( rule, MavenProject.class, mavenProject );

        // rule.apiEndpoint = "http://mock";
        rule.maxAge = "1w";
        rule.warnAge = "3d";

        // mock

        // run test
        assertThatThrownBy( rule::execute ).isInstanceOf( EnforcerRuleException.class ).hasMessageContaining( "One or more dependencies of this project are older than the allowed maximum age (1 week)" );

        // verify
        verify( apiClient );
    }

    private static <T> void inject(Object bean, Class<T> fieldType, T value) throws IllegalAccessException {

        Class<?> currentClass = bean.getClass();

        boolean injected = false;
        do {
            final Field[] fields = currentClass.getDeclaredFields();

            for ( final Field f : fields ) {
                final int m = f.getModifiers();
                final Inject inject = f.getAnnotation( Inject.class );
                if ( inject != null ) {
                    if ( Modifier.isFinal( m ) || Modifier.isStatic( m ) ) {
                        throw new UnsupportedOperationException( "Found invalid @Inject annotation on" +
                            " field " + f + " of class " + bean.getClass() + " - only non-static, non-final fields may be annotated with @Inject" );
                    }
                    if ( f.getType().isAssignableFrom( fieldType ) ) {
                        if ( injected ) {
                            throw new IllegalStateException( "Multiple fields accepting " + fieldType + " are annotated with @Inject?" );
                        }
                        injected = true;
                        f.setAccessible( true );
                        f.set( bean, value );
                    }
                }
            }
            currentClass = currentClass.getSuperclass();
        } while (currentClass != null && currentClass != Object.class);
        if ( ! injected  ) {
            throw new IllegalStateException( "Failed to find any @Inject field suitable for injecting " + fieldType );
        }
    }

    private DependencyAgeRule createRule(ZonedDateTime currentTime, IAPIClient apiClient) throws IOException
    {
        // setup
        final DependencyAgeRule rule = new DependencyAgeRule() {
            @Override
            protected IAPIClient getLocalAPIClient(boolean debug) {
                return apiClient;
            }

            @Override
            protected ZonedDateTime currentTime() {
                return currentTime;
            }
        };
        final EnforcerLogger logger = EasyMock.createNiceMock( EnforcerLogger.class );
        EasyMock.replay( logger );
        rule.setLog( logger );
        return rule;
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