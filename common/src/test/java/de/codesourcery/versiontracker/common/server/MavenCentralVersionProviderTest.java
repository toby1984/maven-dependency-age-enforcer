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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

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
      <version>3.12.0</version>
    </versions>
    <lastUpdated>20210301214036</lastUpdated>
  </versioning>
</metadata>""";

        final String metaDataURL = "/"+MavenCentralVersionProvider.metaDataPath( info.artifact );
        stubFor(get( metaDataURL ).willReturn( ok( metadata ) ) );

        final ZonedDateTime releaseDate1 = date( "2021-07-11 11:12" );
        final ZonedDateTime releaseDate2 = date( "2021-07-12 12:13" );

        final String jsonResponse = loadJSONResponse();
        final String expectedRestURL = "/select/?q=g%3Aorg.apache.commons+AND+a%3Acommons-lang3&core=gav&rows=300&wt=json";
        stubFor( get( expectedRestURL ).willReturn( ok( jsonResponse ) ) );

        if ( scrapeAdditionalReleaseDates ) {
            final String jsonResponse2 = loadJSONResponse();
            final String expectedRestURL2 = "/select/?q=g%3Aorg.apache.commons+AND+a%3Acommons-lang3&core=gav&rows=300&wt=json";
            stubFor( get( expectedRestURL2 ).willReturn( ok( jsonResponse2 ) ) );
        }

        final IVersionProvider.UpdateResult result = provider.update( info, false );
        assertThat(result).isEqualTo( IVersionProvider.UpdateResult.UPDATED );
        assertThat(info.versions).isNotEmpty();
        Assertions.assertTrue(info.versions.stream().anyMatch( x -> x.versionString.equals("3.11") ) );
        Assertions.assertTrue(info.versions.stream().anyMatch( x -> x.versionString.equals("3.12.0") ) );

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