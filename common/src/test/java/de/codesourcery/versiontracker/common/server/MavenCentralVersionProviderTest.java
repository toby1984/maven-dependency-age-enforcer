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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
public class MavenCentralVersionProviderTest {

    @Test
    public void testScrapingOnlyLatestVersions(WireMockRuntimeInfo webServer) throws IOException {
        doTest(webServer, false );
    }

    @Test
    public void testScrapeAdditional(WireMockRuntimeInfo webServer) throws IOException {
        doTest(webServer, true );
    }

    private void doTest(WireMockRuntimeInfo webServer, boolean scrapeAdditionalReleaseDates) throws IOException {

        final String restApiBaseUrl = "http://localhost:" + webServer.getHttpPort() + "/select";
        final String repo1BaseUrl = "http://localhost:" + webServer.getHttpPort();

        final MavenCentralVersionProvider provider = new MavenCentralVersionProvider( repo1BaseUrl, restApiBaseUrl );

        final VersionInfo info = new VersionInfo();
        info.artifact = new Artifact();
        info.artifact.groupId="org.apache.commons";
        info.artifact.artifactId="commons-lang3";
        info.artifact.version="3.11";

        // TODO: Also write unit test for artifact with classifier

        final String metadata = """
<metadata>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-lang3</artifactId>
  <versioning>
    <latest>3.12.0</latest>
    <release>3.12.0</release>
    <versions>
      <version>3.11</version>
      <version>3.11.1</version>
      <version>3.12.0</version>
    </versions>
    <lastUpdated>20210301214036</lastUpdated>
  </versioning>
</metadata>""";

        final String metaDataURL = "/"+MavenCentralVersionProvider.metaDataPath( info.artifact );
        stubFor(get( metaDataURL ).willReturn( ok( metadata ) ) );


        final ZonedDateTime releaseDate1 = date( "2021-07-11 11:12" );
        final ZonedDateTime releaseDate2 = date( "2021-07-12 12:13" );

        final String jsonResponse = """
{"responseHeader":{"status":0,"QTime":3,"params":{"q":"g:org.apache.commons AND a:commons-lang3 AND v: 3.12.0","core":"","indent":"off","fl":"id,g,a,v,p,ec,timestamp,tags","start":"","sort":"score desc,timestamp desc,g asc,a asc,v desc","rows":"20","wt":"json","version":"2.2"}},"response":{"numFound":1,"start":0,"docs":[{"id":"org.apache.commons:commons-lang3:3.12.0","g":"org.apache.commons","a":"commons-lang3","v":"3.12.0","p":"jar","timestamp":1626091980000,"ec":["-sources.jar","-javadoc.jar","-test-sources.jar",".jar","-tests.jar",".pom"],"tags":["classes","standard","justify","package","apache","lang","considered","existence","that","utility","commons","java","hierarchy"]}]}}            
            """;
        final String expectedRestURL = "/select/?q=g%3Aorg.apache.commons+AND+a%3Acommons-lang3+AND+v%3A+3.12.0&rows=10000&wt=json";
        stubFor( get( expectedRestURL ).willReturn( ok( jsonResponse ) ) );

        if ( scrapeAdditionalReleaseDates ) {
            final String jsonResponse2 = """
{"responseHeader":{"status":0,"QTime":3,"params":{"q":"g:org.apache.commons AND a:commons-lang3 AND v: 3.11","core":"","indent":"off","fl":"id,g,a,v,p,ec,timestamp,tags","start":"","sort":"score desc,timestamp desc,g asc,a asc,v desc","rows":"20","wt":"json","version":"2.2"}},"response":{"numFound":1,"start":0,"docs":[{"id":"org.apache.commons:commons-lang3:3.11","g":"org.apache.commons","a":"commons-lang3","v":"3.11","p":"jar","timestamp":1626001920000,"ec":["-sources.jar","-javadoc.jar","-test-sources.jar",".jar","-tests.jar",".pom"],"tags":["classes","standard","justify","package","apache","lang","considered","existence","that","utility","commons","java","hierarchy"]}]}}            
            """;
            final String expectedRestURL2 = "/select/?q=g%3Aorg.apache.commons+AND+a%3Acommons-lang3+AND+v%3A+3.11&rows=10000&wt=json";
            stubFor( get( expectedRestURL2 ).willReturn( ok( jsonResponse2 ) ) );
        }

        final IVersionProvider.UpdateResult result = provider.update( info, scrapeAdditionalReleaseDates ? Set.of( "3.11" ) : Collections.emptySet() );
        assertThat(result).isEqualTo( IVersionProvider.UpdateResult.UPDATED );
        assertThat(info.versions).isNotEmpty();
        assertThat(info.versions).containsExactlyInAnyOrder( Version.of("3.11"), Version.of("3.11.1"), Version.of("3.12.0") );
        if ( scrapeAdditionalReleaseDates ) {
            assertThat( info.getVersion( "3.11" ) ).map( x -> x.releaseDate ).hasValueSatisfying( v -> v.toInstant().equals( releaseDate1.toInstant() ) );
        } else {
            assertThat( info.getVersion( "3.11" ) ).isPresent();
        }
        assertThat( info.getVersion( "3.12.0" ) ).map( x -> x.releaseDate ).hasValueSatisfying( v -> v.toInstant().equals( releaseDate2.toInstant() ) );

        verify( getRequestedFor( urlEqualTo( metaDataURL ) ) );
    }

    private static ZonedDateTime date(String s) {
        final DateTimeFormatter format = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm" );
        return ZonedDateTime.parse( s, format.withZone( ZoneId.of("UTC") ) );
    }

    private static String createMarkupForScraping(Artifact artifact, String versionNumber, ZonedDateTime releaseDate) {
        /*
         * <a href="junit-4.12-javadoc.jar" title="junit-4.12-javadoc.jar">junit-4.12-javadoc.jar</a>
         *                             2014-12-04 16:17    937942
         */
        final String prefix = artifact.artifactId+"-"+versionNumber+"-javadoc.jar";
        DateTimeFormatter format = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm" );
        return """
<a href="%s" title="%s">%s</a>
         %s    937942""".formatted(prefix,prefix,prefix, releaseDate.format( format ));
    }
}