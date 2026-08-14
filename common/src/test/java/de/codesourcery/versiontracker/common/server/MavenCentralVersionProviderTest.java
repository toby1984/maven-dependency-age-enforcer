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
import de.codesourcery.versiontracker.common.server.MavenCentralVersionProvider.RateLimit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    public void rateLimitPerSecondConvertsToMillis() {
        assertThat( new RateLimit( 2, TimeUnit.SECONDS ).getMinMillisBetweenRequests() ).isEqualTo( 500L );
    }

    @Test
    public void rateLimitPerMinuteConvertsToMillis() {
        assertThat( new RateLimit( 60, TimeUnit.MINUTES ).getMinMillisBetweenRequests() ).isEqualTo( 1000L );
    }

    @Test
    public void rateLimitPerHourConvertsToMillis() {
        assertThat( new RateLimit( 1, TimeUnit.HOURS ).getMinMillisBetweenRequests() ).isEqualTo( 60 * 60 * 1000L );
    }

    @Test
    public void rateLimitPerDayConvertsToMillis() {
        assertThat( new RateLimit( 1, TimeUnit.DAYS ).getMinMillisBetweenRequests() ).isEqualTo( 24 * 60 * 60 * 1000L );
    }

    /**
     * If the maven-metadata.xml did not change since the last update,
     * no release dates must be queried at all.
     */
    @Test
    public void testNoChangesOnServerSkipsReleaseDateQueries(WireMockRuntimeInfo webServer) throws IOException {

        final VersionInfo info = newVersionInfo();
        stubMetaData( info.artifact, "3.9", "3.10", "3.11", "3.12.0" );
        // matches the <lastUpdated>20210301214036</lastUpdated> value written by stubMetaData()
        info.lastRepositoryUpdate = ZonedDateTime.of( 2021, 3, 1, 21, 40, 36, 0, ZoneId.of( "UTC" ) );

        final IVersionProvider.UpdateResult result = newProvider( webServer ).update( info, false );

        assertThat( result ).isEqualTo( IVersionProvider.UpdateResult.NO_CHANGES_ON_SERVER );
        assertThat( info.lastSuccessDate ).isNotNull();
        verify( 0, getRequestedFor( urlPathEqualTo( "/select" ) ) );
    }

    @Test
    public void testMissingArtifactYieldsArtifactUnknown(WireMockRuntimeInfo webServer) throws IOException {

        final VersionInfo info = newVersionInfo();
        // no maven-metadata.xml stubbed => server responds with HTTP 404

        final IVersionProvider.UpdateResult result = newProvider( webServer ).update( info, false );

        assertThat( result ).isEqualTo( IVersionProvider.UpdateResult.ARTIFACT_UNKNOWN );
        assertThat( info.lastFailureDate ).isNotNull();
        assertThat( info.lastSuccessDate ).isNull();
    }

    @Test
    public void testRequestedVersionNotInMetaData(WireMockRuntimeInfo webServer) throws IOException {

        final VersionInfo info = newVersionInfo();
        info.artifact.version = "9.9.9";
        stubMetaData( info.artifact, "3.9", "3.10", "3.11", "3.12.0" );
        stubFor( get( BULK_REST_URL ).willReturn( ok( loadJSONResponse() ) ) );

        final IVersionProvider.UpdateResult result = newProvider( webServer ).update( info, false );

        assertThat( result ).isEqualTo( IVersionProvider.UpdateResult.ARTIFACT_VERSION_NOT_FOUND );
        assertThat( info.lastSuccessDate ).isNotNull();
    }

    /**
     * Versions that already have a release date must not be queried again
     * and their known release date must be retained.
     */
    @Test
    public void testKnownReleaseDatesAreNotQueriedAgain(WireMockRuntimeInfo webServer) throws IOException {

        final long sentinel39 = 1234567890000L;
        final long sentinel310 = 1234567891000L;

        final VersionInfo info = newVersionInfo();
        info.versions.add( new Version( "3.9", Instant.ofEpochMilli( sentinel39 ).atZone( ZoneId.systemDefault() ) ) );
        info.versions.add( new Version( "3.10", Instant.ofEpochMilli( sentinel310 ).atZone( ZoneId.systemDefault() ) ) );

        stubMetaData( info.artifact, "3.9", "3.10", "3.11", "3.12.0" );
        final String url311 = singleVersionRestURL( "3.11" );
        final String url3120 = singleVersionRestURL( "3.12.0" );
        stubFor( get( url311 ).willReturn( ok( singleVersionJSONResponse( "3.11", TIMESTAMP_3_11 ) ) ) );
        stubFor( get( url3120 ).willReturn( ok( singleVersionJSONResponse( "3.12.0", TIMESTAMP_3_12_0 ) ) ) );

        final IVersionProvider.UpdateResult result = newProvider( webServer ).update( info, false );

        assertThat( result ).isEqualTo( IVersionProvider.UpdateResult.UPDATED );
        assertReleaseDate( info, "3.9", sentinel39 );
        assertReleaseDate( info, "3.10", sentinel310 );
        assertReleaseDate( info, "3.11", TIMESTAMP_3_11 );
        assertReleaseDate( info, "3.12.0", TIMESTAMP_3_12_0 );
        // only the two versions lacking a release date may be queried
        verify( 2, getRequestedFor( urlPathEqualTo( "/select" ) ) );
    }

    /**
     * If one of several Solr queries fails, the update must be reported as failed.
     */
    @Test
    public void testPartialFailureOfIndividualQueriesFailsTheUpdate(WireMockRuntimeInfo webServer) {

        final VersionInfo info = newVersionInfo();
        stubMetaData( info.artifact, "3.11", "3.12.0" );
        stubFor( get( singleVersionRestURL( "3.11" ) ).willReturn( ok( singleVersionJSONResponse( "3.11", TIMESTAMP_3_11 ) ) ) );
        stubFor( get( singleVersionRestURL( "3.12.0" ) ).willReturn( serverError() ) );

        assertThatThrownBy( () -> newProvider( webServer ).update( info, false ) ).isInstanceOf( IOException.class );

        assertThat( info.lastFailureDate ).isNotNull();
        assertThat( info.lastSuccessDate ).isNull();
        assertThat( info.lastRepositoryUpdate ).isNull();
    }

    /**
     * The Sonatype API returns at most {@link SonatypeRestAPIUrlBuilder#DEFAULT_MAX_RESULTS_PER_REQUEST}
     * results per request, a bulk query has to page through the results to get all of them.
     */
    @Test
    public void testBulkQueryPagesThroughResults(WireMockRuntimeInfo webServer) throws IOException {

        final VersionInfo info = newVersionInfo();
        info.artifact.version = "1.0.1";
        stubMetaData( info.artifact, generatedVersions( 45 ) );

        stubFor( get( bulkRestURL( null ) ).willReturn( ok( bulkJSONResponse( 45, 1, 30 ) ) ) );
        stubFor( get( bulkRestURL( 1 ) ).willReturn( ok( bulkJSONResponse( 45, 31, 45 ) ) ) );

        final IVersionProvider.UpdateResult result = newProvider( webServer ).update( info, false );

        assertThat( result ).isEqualTo( IVersionProvider.UpdateResult.UPDATED );
        assertThat( info.versions ).hasSize( 45 );
        for ( int i = 1 ; i <= 45 ; i++ ) {
            assertReleaseDate( info, "1.0." + i, timestampOf( i ) );
        }
    }

    /**
     * Paging must also work when the result set spans more than two pages.
     */
    @Test
    public void testBulkQueryPagesThroughMoreThanTwoPages(WireMockRuntimeInfo webServer) throws IOException {

        final VersionInfo info = newVersionInfo();
        info.artifact.version = "1.0.1";
        stubMetaData( info.artifact, generatedVersions( 75 ) );

        stubFor( get( bulkRestURL( null ) ).willReturn( ok( bulkJSONResponse( 75, 1, 30 ) ) ) );
        stubFor( get( bulkRestURL( 1 ) ).willReturn( ok( bulkJSONResponse( 75, 31, 60 ) ) ) );
        stubFor( get( bulkRestURL( 2 ) ).willReturn( ok( bulkJSONResponse( 75, 61, 75 ) ) ) );

        final IVersionProvider.UpdateResult result = newProvider( webServer ).update( info, false );

        assertThat( result ).isEqualTo( IVersionProvider.UpdateResult.UPDATED );
        assertThat( info.versions ).hasSize( 75 );
        for ( int i = 1 ; i <= 75 ; i++ ) {
            assertReleaseDate( info, "1.0." + i, timestampOf( i ) );
        }
    }

    /**
     * If fetching one of the result pages fails, the versions retrieved so far
     * are kept but the update must be reported as failed.
     */
    @Test
    public void testBulkQueryPartialFailureKeepsPartialResult(WireMockRuntimeInfo webServer) {

        final VersionInfo info = newVersionInfo();
        info.artifact.version = "1.0.1";
        stubMetaData( info.artifact, generatedVersions( 45 ) );

        stubFor( get( bulkRestURL( null ) ).willReturn( ok( bulkJSONResponse( 45, 1, 30 ) ) ) );
        stubFor( get( bulkRestURL( 1 ) ).willReturn( serverError() ) );

        assertThatThrownBy( () -> newProvider( webServer ).update( info, false ) ).isInstanceOf( IOException.class );

        assertThat( info.lastFailureDate ).isNotNull();
        assertThat( info.lastSuccessDate ).isNull();
        assertThat( info.versions ).hasSize( 30 );
        for ( int i = 1 ; i <= 30 ; i++ ) {
            assertReleaseDate( info, "1.0." + i, timestampOf( i ) );
        }
    }

    private MavenCentralVersionProvider newProvider(WireMockRuntimeInfo webServer) {
        final String repo1BaseUrl = "http://localhost:" + webServer.getHttpPort();
        final String restApiBaseUrl = "http://localhost:" + webServer.getHttpPort() + "/select";

        final MavenCentralVersionProvider provider = new MavenCentralVersionProvider( repo1BaseUrl, restApiBaseUrl );
        provider.setConfigurationProvider( configProvider );
        // tests talk to a local WireMock server, no need to rate-limit anything
        provider.setSolrApiRateLimit( new RateLimit( 1000, TimeUnit.SECONDS ) );
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

    private static String[] generatedVersions(int count) {
        return IntStream.rangeClosed( 1, count ).mapToObj( i -> "1.0." + i ).toArray( String[]::new );
    }

    private static long timestampOf(int versionIndex) {
        return 1600000000000L + versionIndex * 1000L;
    }

    /**
     * @param pageNumber zero-based result page, <code>null</code> to omit the parameter (=&gt; first page)
     */
    private static String bulkRestURL(Integer pageNumber) {
        return "/select?q=g%3Aorg.apache.commons+AND+a%3Acommons-lang3&core=gav"
               + (pageNumber == null ? "" : "&start=" + pageNumber)
               + "&rows=30&wt=json";
    }

    /**
     * @return Solr response containing one doc for each of the versions <code>1.0.&lt;firstVersion&gt;</code>
     *         up to (and including) <code>1.0.&lt;lastVersion&gt;</code>
     */
    private static String bulkJSONResponse(int numFound, int firstVersion, int lastVersion) {
        final String docs = IntStream.rangeClosed( firstVersion, lastVersion )
            .mapToObj( i -> """
{"id":"org.apache.commons:commons-lang3:1.0.%d","g":"org.apache.commons","a":"commons-lang3",\
"v":"1.0.%d","p":"jar","timestamp":%d,"ec":[".pom",".jar"]}""".formatted( i, i, timestampOf( i ) ) )
            .collect( Collectors.joining( "," ) );
        return """
{"responseHeader":{"status":0},"response":{"numFound":%d,"start":0,"docs":[%s]}}""".formatted( numFound, docs );
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
