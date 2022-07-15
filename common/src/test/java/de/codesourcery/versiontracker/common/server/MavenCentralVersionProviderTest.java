package de.codesourcery.versiontracker.common.server;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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

        final MavenCentralVersionProvider provider = new MavenCentralVersionProvider("http://localhost:"+webServer.getHttpPort());

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

        final String scapingURL1 = "/" + MavenCentralVersionProvider.getPathToFolder( info.artifact, "3.11" );
        final ZonedDateTime releaseDate1 = date( "2021-07-11 11:12" );
        if ( scrapeAdditionalReleaseDates ) {
            stubFor( get( scapingURL1 ).willReturn( ok( createMarkupForScraping( info.artifact, "3.11", releaseDate1 ) ) ) );
        }

        final String scapingURL2 = "/" + MavenCentralVersionProvider.getPathToFolder( info.artifact, "3.12.0" );
        final ZonedDateTime releaseDate2 = date( "2021-07-12 12:13" );
        stubFor(get( scapingURL2 ).willReturn( ok( createMarkupForScraping( info.artifact,"3.12.0", releaseDate2 ) ) ) );

        final IVersionProvider.UpdateResult result = provider.update( info, scrapeAdditionalReleaseDates ? Set.of( "3.11" ) : Collections.emptySet() );
        assertThat(result).isEqualTo( IVersionProvider.UpdateResult.UPDATED );
        assertThat(info.versions).isNotEmpty();
        assertThat(info.versions).hasSize( 2 );
        assertThat(info.versions).containsExactlyInAnyOrder( Version.of("3.11"), Version.of("3.12.0") );
        if ( scrapeAdditionalReleaseDates ) {
            assertThat( info.getDetails( "3.11" ) ).map( x -> x.releaseDate ).hasValue( releaseDate1 );
        } else {
            assertThat( info.getDetails( "3.11" ) ).isPresent();
        }
        assertThat( info.getDetails( "3.12.0" ) ).map( x -> x.releaseDate ).hasValue( releaseDate2 );

        if ( scrapeAdditionalReleaseDates ) {
            verify( getRequestedFor( urlEqualTo( scapingURL1 ) ) );
        }
        verify( getRequestedFor( urlEqualTo( scapingURL2 ) ) );
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