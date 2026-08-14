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

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
public class MavenCentralVersionProviderTest {

    // release timestamps as found in /response.json
    private static final long TIMESTAMP_3_9 = 1554946239000L;
    private static final long TIMESTAMP_3_10 = 1584970776000L;
    private static final long TIMESTAMP_3_11 = 1594560722000L;
    private static final long TIMESTAMP_3_12_0 = 1614372052000L;

    private static final String BULK_REST_URL =
        "/select?q=g%3Aorg.apache.commons+AND+a%3Acommons-lang3&core=gav&rows=30&wt=json";

    private ConfigurationProvider configProvider;

    @BeforeEach
    void setup() {
        configProvider = new ConfigurationProvider();
    }

    @AfterEach
    void tearDown() throws InterruptedException
    {
        configProvider.close();
    }

    /**
     * More than three versions without a release date => one bulk Solr query for all versions.
     */
    @Test
    public void testScrapingVersionsUsingBulkQuery(WireMockRuntimeInfo webServer) throws IOException {

        final VersionInfo info = newVersionInfo();
        final String metaDataURL = stubMetaData( info.artifact, "3.9", "3.10", "3.11", "3.12.0" );

        stubFor( get( BULK_REST_URL ).willReturn( ok( loadJSONResponse() ) ) );

        final IVersionProvider.UpdateResult result = newProvider( webServer ).update( info, false );

        assertThat( result ).isEqualTo( IVersionProvider.UpdateResult.UPDATED );
        assertThat( info.versions ).extracting( x -> x.versionString )
            .containsExactlyInAnyOrder( "3.9", "3.10", "3.11", "3.12.0" );
        assertReleaseDate( info, "3.9", TIMESTAMP_3_9 );
        assertReleaseDate( info, "3.10", TIMESTAMP_3_10 );
        assertReleaseDate( info, "3.11", TIMESTAMP_3_11 );
        assertReleaseDate( info, "3.12.0", TIMESTAMP_3_12_0 );

        verify( getRequestedFor( urlEqualTo( metaDataURL ) ) );
        verify( getRequestedFor( urlEqualTo( BULK_REST_URL ) ) );
    }

    /**
     * Three versions or less without a release date => one Solr query per version.
     */
    @Test
    public void testScrapingVersionsUsingIndividualQueries(WireMockRuntimeInfo webServer) throws IOException {

        final VersionInfo info = newVersionInfo();
        final String metaDataURL = stubMetaData( info.artifact, "3.11", "3.12.0" );

        final String url311 = singleVersionRestURL( "3.11" );
        final String url3120 = singleVersionRestURL( "3.12.0" );
        stubFor( get( url311 ).willReturn( ok( singleVersionJSONResponse( "3.11", TIMESTAMP_3_11 ) ) ) );
        stubFor( get( url3120 ).willReturn( ok( singleVersionJSONResponse( "3.12.0", TIMESTAMP_3_12_0 ) ) ) );

        final IVersionProvider.UpdateResult result = newProvider( webServer ).update( info, false );

        assertThat( result ).isEqualTo( IVersionProvider.UpdateResult.UPDATED );
        assertThat( info.versions ).extracting( x -> x.versionString )
            .containsExactlyInAnyOrder( "3.11", "3.12.0" );
        assertReleaseDate( info, "3.11", TIMESTAMP_3_11 );
        assertReleaseDate( info, "3.12.0", TIMESTAMP_3_12_0 );

        verify( getRequestedFor( urlEqualTo( metaDataURL ) ) );
        verify( getRequestedFor( urlEqualTo( url311 ) ) );
        verify( getRequestedFor( urlEqualTo( url3120 ) ) );
    }

    private MavenCentralVersionProvider newProvider(WireMockRuntimeInfo webServer) {
        final String repo1BaseUrl = "http://localhost:" + webServer.getHttpPort();
        final String restApiBaseUrl = "http://localhost:" + webServer.getHttpPort() + "/select";

        final MavenCentralVersionProvider provider = new MavenCentralVersionProvider( repo1BaseUrl, restApiBaseUrl );
        provider.setConfigurationProvider( configProvider );
        return provider;
    }

    // TODO: Also write unit test for artifact with classifier
    private static VersionInfo newVersionInfo() {
        final VersionInfo info = new VersionInfo();
        info.artifact = new Artifact();
        info.artifact.groupId = "org.apache.commons";
        info.artifact.artifactId = "commons-lang3";
        info.artifact.version = "3.11";
        return info;
    }

    /**
     * @return the stubbed maven-metadata.xml URL
     */
    private static String stubMetaData(Artifact artifact, String... versions) {
        final String versionTags = String.join( "\n      ",
            List.of( versions ).stream().map( v -> "<version>" + v + "</version>" ).toList() );

        final String metadata = """
<metadata>
  <groupId>%s</groupId>
  <artifactId>%s</artifactId>
  <versioning>
    <latest>%s</latest>
    <release>%s</release>
    <versions>
      %s
    </versions>
    <lastUpdated>20210301214036</lastUpdated>
  </versioning>
</metadata>""".formatted( artifact.groupId, artifact.artifactId,
            versions[versions.length - 1], versions[versions.length - 1], versionTags );

        final String metaDataURL = "/" + MavenCentralVersionProvider.metaDataPath( artifact );
        stubFor( get( metaDataURL ).willReturn( ok( metadata ) ) );
        return metaDataURL;
    }

    private static String singleVersionRestURL(String version) {
        return "/select?q=g%3Aorg.apache.commons+AND+a%3Acommons-lang3+AND+v%3A" + version + "&rows=30&wt=json";
    }

    private static String singleVersionJSONResponse(String version, long timestamp) {
        return """
{"responseHeader":{"status":0},"response":{"numFound":1,"start":0,"docs":[\
{"id":"org.apache.commons:commons-lang3:%s","g":"org.apache.commons","a":"commons-lang3",\
"v":"%s","p":"jar","timestamp":%d,"ec":[".pom",".jar"]}]}}""".formatted( version, version, timestamp );
    }

    private static void assertReleaseDate(VersionInfo info, String version, long expectedTimestamp) {
        assertThat( info.getVersion( version ) ).isPresent()
            .get().extracting( x -> ((Version) x).releaseDate.toInstant() )
            .isEqualTo( Instant.ofEpochMilli( expectedTimestamp ) );
    }

    private static String loadJSONResponse() throws IOException
    {
        try ( InputStream in = MavenCentralVersionProviderTest.class.getResourceAsStream( "/response.json" ) ) {
            if ( in == null ) {
                throw new FileNotFoundException( "Failed to find response.json" );
            }
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
    }
}
