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

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.commons.io.input.TeeInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.JSONHelper;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;

/**
 * Version provider that retrieves artifact metadata from Maven central.
 * 
 * This class is thread-safe.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class MavenCentralVersionProvider implements IVersionProvider
{
    private static final Logger LOG = LogManager.getLogger(MavenCentralVersionProvider.class);

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("UTC"));

    private static final String REPO1_HOST = "repo1.maven.org";
    public static final String DEFAULT_REPO1_BASE_URL = "https://"+REPO1_HOST+"/maven2/";
    // public static final String DEFAULT_SONATYPE_REST_API_BASE_URL = "https://search.maven.org/solrsearch/select";
    public static final String DEFAULT_SONATYPE_REST_API_BASE_URL = "https://central.sonatype.com/solrsearch/select";

    public record RateLimit(double requestsPerInterval, TimeUnit intervalUnit) {
        public RateLimit
        {
            Validate.isTrue( requestsPerInterval > 0 );
            Validate.notNull( intervalUnit, "unit must not be null" );
        }

        @Override
        public String toString()
        {
            return requestsPerInterval + " requests/" + intervalUnit.name().toLowerCase();
        }

        public long getMinMillisBetweenRequests() {
            final long intervalInMillis = switch( intervalUnit ) {
                case SECONDS -> 1000;
                case MINUTES -> 1000*60;
                case HOURS -> 60*60*24;
                case DAYS -> 1000*60*60*24;
                default -> throw new RuntimeException("Unrechable code reached");
            };
            return (long) (intervalInMillis / requestsPerInterval);
        }
    }

    private static final class FifoRateLimiter
    {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final long minIntervalMillis;
        private long lastExecutionTimestamp = 0;

        public FifoRateLimiter(RateLimit rateLimit) {
            this.minIntervalMillis = rateLimit.getMinMillisBetweenRequests();
        }

        public void acquire() throws InterruptedException {
            lock.lock(); // Queues incoming threads fairly
            try {
                final long now = System.currentTimeMillis();
                final long elapsed = now - lastExecutionTimestamp;
                final long waitTime = minIntervalMillis - elapsed;

                if (waitTime > 0) {
                    Thread.sleep(waitTime);
                }
                // Update timestamp after the delay
                lastExecutionTimestamp = System.currentTimeMillis();
            } finally {
                lock.unlock(); // Hands control to the next thread in queue
            }
        }
    }

    private static final class RequestCount implements IRequestCount
    {
        public int count;
        public ZonedDateTime timestamp;

        public RequestCount()
        {
            this.count = 1;
            this.timestamp = ZonedDateTime.now();
        }

        private RequestCount(RequestCount other)
        {
            this.count = other.count;
            this.timestamp = other.timestamp;
        }

        public RequestCount incCount() {
            this.count++;
            this.timestamp = ZonedDateTime.now();
            return this;
        }

        @Override
        public int requestCount()
        {
            return count;
        }

        @Override
        public ZonedDateTime latestRequestTimestamp()
        {
            return timestamp;
        }

        @Override
        public IRequestCount createCopy()
        {
            return new RequestCount(this);
        }
    }

    /**
     * HTTP GET parameters used by Sonatype REST API.
     *
     * <p>There's basically no documentation for this except https://central.sonatype.org/search/rest-api-guide/</p>
     *
     *
     * @author tobias.gierke@code-sourcery.de
     */
    enum HttpParam {
        QUERY("q",1),
        /** return all versions of an artifact */
        OPT_RETURN_ALL_VERSION("core","gav", 2 ),
        START_OFFSET("start",3),
        MAX_RESULT_COUNT("rows",4),
        RESULT_TYPE("wt", "json", 5 )
        ;
        public final String literal;
        public final String value;
        public final int order;

        HttpParam(String literal, int order) {
            this( literal, null, order );
        }

        HttpParam(String literal, String value, int order)
        {
            this.literal = literal;
            this.value = value;
            this.order = order;
        }
    }

    private static final ObjectMapper JSON_MAPPER =  JSONHelper.newObjectMapper();

    private static final class MyExpressions // XPathExpression is NOT thread-safe so we use a ThreadLocal + this wrapper
    {
        private final XPathExpression latestSnapshot;
        private final XPathExpression latestRelease;
        private final XPathExpression lastUpdateDate;
        private final XPathExpression allVersions;

        public MyExpressions()
        {
            final XPathFactory factory = XPathFactory.newInstance();
            final XPath xpath = factory.newXPath();

            try {
                latestSnapshot = xpath.compile("/metadata/versioning/latest[text()]");
                latestRelease = xpath.compile("/metadata/versioning/release[text()]");
                lastUpdateDate = xpath.compile("/metadata/versioning/lastUpdated");
                allVersions = xpath.compile("/metadata/versioning/versions/version/text()");
            } catch (XPathExpressionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @FunctionalInterface
    public interface MyStreamHandler<T>
    {
        T process(InputStream stream) throws IOException;
    }

    private final PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();    
    private final String repo1BaseUrl;
    private final String sonatypeRestApiBaseUrl;
    private final ThreadLocal<MyExpressions> expressions = ThreadLocal.withInitial( MyExpressions::new );

    private final Map<String,CloseableHttpClient> clients = new HashMap<>();
    private final Map<String,FifoRateLimiter> rateLimiters = new HashMap<>();

    // be gentle, Sonatype has aggressive rate limiting
    private RateLimit solrApiRateLimit = new RateLimit(60,TimeUnit.MINUTES); // 60 requests/minute

    // @GuardedBy( statistics )
    private final Statistics statistics = new Statistics();

    private ConfigurationProvider configurationProvider;

    public MavenCentralVersionProvider()
    {
        this( DEFAULT_REPO1_BASE_URL, DEFAULT_SONATYPE_REST_API_BASE_URL);
        connManager.setDefaultMaxPerRoute(10);
        connManager.setMaxTotal(20);

        final ConnectionConfig connConfig = ConnectionConfig.custom()
            .setConnectTimeout( Timeout.ofSeconds(15))
            .setSocketTimeout( Timeout.ofSeconds(30) )
            .setValidateAfterInactivity( TimeValue.ofSeconds(10) )
            .setIdleTimeout( Timeout.ofMinutes(1) )
            .setTimeToLive( TimeValue.ofMinutes(10))
            .build();

        connManager.setDefaultConnectionConfig( connConfig );
    }

    public MavenCentralVersionProvider(String repo1BaseUrl, String sonatypeRestApiBaseUrl)
    {
        Validate.notBlank( repo1BaseUrl, "repo1BaseUrl must not be null or blank");
        Validate.notBlank( sonatypeRestApiBaseUrl, "sonatypeRestApiBaseUrl must not be null or blank");
        this.repo1BaseUrl = repo1BaseUrl+(repo1BaseUrl.trim().endsWith("/") ? "" : "/" );
        this.sonatypeRestApiBaseUrl = sonatypeRestApiBaseUrl;
    }

    @Override
    public void setConfigurationProvider(ConfigurationProvider configurationProvider)
    {
        Validate.notNull( configurationProvider, "configurationProvider must not be null" );
        this.configurationProvider = configurationProvider;
    }

    public static void main(String[] args)
    {
        System.out.println(" ================= NOW: "+ZonedDateTime.now());

        final ConfigurationProvider configProvider = new ConfigurationProvider();

        final Artifact test = new Artifact();

        // https://repo1.maven.org/maven2/de/code-sourcery/versiontracker/versiontracker-common/
        test.groupId = "de.code-sourcery.versiontracker";
        test.artifactId = "versiontracker-common";

        VersionInfo data = new VersionInfo();
        data.artifact = test;
        long start = System.currentTimeMillis();
        final MavenCentralVersionProvider provider = new MavenCentralVersionProvider();
        provider.setConfigurationProvider( configProvider );
        UpdateResult result = null;
        try
        {
            result = provider.update( data, false );
        } catch(IOException e) {
            e.printStackTrace();
        }
        long end = System.currentTimeMillis();
        System.out.println("TIME: "+(end-start)+" ms");
        System.out.println("RESULT: "+result);
        System.out.println("GOT: "+data);

        System.out.println( "VERSION COUNT: " + data.versions.size() );

        data.versions.stream().sorted( (a, b) -> a.versionString.compareToIgnoreCase( b.versionString ) )
            .forEach( x -> System.out.println( x.versionString + " => " + x.releaseDate ) );
    }

    private boolean isBlacklisted(Artifact a) {
        return configurationProvider.getConfiguration().getBlacklist().isAllVersionsBlacklisted( a.groupId, a.artifactId );
    }

    @Override
    public UpdateResult update(VersionInfo info, boolean force) throws IOException
    {
        final Artifact artifact = info.artifact;

        if ( isBlacklisted( artifact ) ) {
            if ( LOG.isDebugEnabled() ) {
                LOG.debug( "update(): Not updating blacklisted artifact " + artifact );
            }
            info.lastSuccessDate = ZonedDateTime.now();
            info.nextBackgroundUpdate = null;
            return UpdateResult.BLACKLISTED;
        }

        final URL url = new URL( repo1BaseUrl +metaDataPath( artifact ) );

        if ( LOG.isDebugEnabled() ) {
            LOG.debug("update(): Retrieving metadata for "+info.artifact+" from "+url);
        }

        try
        {
            synchronized ( statistics ) {
                statistics.metaDataRequests.update();
            }
            return performGET(url, stream -> {
                final Document document = parseXML( stream );

                // parse latest snapshot & release versions from metadata
                final MyExpressions expr = expressions.get();

                // last repository change date
                final String lastChangeString = readString(expr.lastUpdateDate, document );
                LOG.debug("update(): last repository change = "+lastChangeString);

                final ZonedDateTime lastChangeDate = ZonedDateTime.parse( lastChangeString, DATE_FORMATTER);
                final ZonedDateTime previousUpdate = info.lastRepositoryUpdate;

                if ( previousUpdate != null && previousUpdate.equals( lastChangeDate ) ) {
                    if ( ! force ) {
                        LOG.debug( "update(): No changes on server.");
                        info.lastSuccessDate = ZonedDateTime.now();
                        return UpdateResult.NO_CHANGES_ON_SERVER;
                    }
                    LOG.debug( "update(): Forced artifact update" );
                } else {
                    LOG.debug( "update(): Artifact index XML changed on server" );
                }

                // get all version numbers from maven-metadata.xml
                // Since we're talking to Maven Central and Sonatype does not allow uploading
                // SNAPSHOT versions of artifacts, that list is
                // going to contain only release versions.
                final List<String> allVersions = readStrings( expr.allVersions, document );

                // we only need to query release dates for new versions
                // or versions where we did not get a release date yet

                final Set<String> versionsWithoutReleaseDate = new HashSet<>();
                final Map<String,Version> knownVersions = new HashMap<>();
                info.versions.forEach( v -> knownVersions.put( v.versionString, v ) );

                for ( final String version : allVersions )
                {
                    final Version knownVersion = knownVersions.get( version );
                    if ( knownVersion == null || ! knownVersion.hasReleaseDate() ) {
                        versionsWithoutReleaseDate.add( version );
                    }
                }

                // get all versions
                LOG.trace("update(): Querying release dates of {} versions for {}", versionsWithoutReleaseDate.size(), info.artifact);

//              Querying using HTTP HEAD 'last-modified' header MIGHT be faster, but
//              we have to construct the URL ourselves (currently hard-coded for .pom files) which might end up not always
//              being the right one ... better to use the Solr search and weed-out duplicates we may encounter instead of missing some results
//                final PossiblyIncompleteResult versionsWithReleaseDate =
//                    queryReleaseDatesUsingHttpHead( info.artifact, versionsWithoutReleaseDate);

                final PossiblyIncompleteResult versionsWithReleaseDate =
                    queryReleaseDatesUsingSolr( info.artifact, versionsWithoutReleaseDate);

                LOG.debug("update(): [{}] Asked for info about {} versions, got {} (result complete: {})",
                    info.artifact,
                    versionsWithoutReleaseDate.size(),
                    versionsWithReleaseDate.result.size(),
                    versionsWithReleaseDate.isCompleteResult());

                for ( final Version versionFromServer : versionsWithReleaseDate.result )
                {
                    final Version knownVersion = knownVersions.get( versionFromServer.versionString );
                    if ( versionFromServer.hasReleaseDate() ) {
                        if ( knownVersion != null )
                        {
                            knownVersion.releaseDate = versionFromServer.releaseDate;
                        } else {
                            // we discovered a new version
                            info.addVersion( versionFromServer );
                        }
                    } else {
                        info.addVersion(versionFromServer);
                    }
                }

                final String latestSnapshotVersion = readString(expr.latestSnapshot, document );
                if ( StringUtils.isNotBlank( latestSnapshotVersion ) ) {
                    info.getVersion( latestSnapshotVersion )
                        .or( () -> Optional.of( new Version(latestSnapshotVersion, null) ) )
                        .ifPresent( x -> info.latestSnapshotVersion = x );
                }

                final String latestReleaseVersion = readString(expr.latestRelease, document );
                if ( StringUtils.isNotBlank( latestReleaseVersion ) ) {
                    info.getVersion( latestReleaseVersion )
                        .or( () -> Optional.of( new Version(latestReleaseVersion, null) ) )
                        .ifPresent( x -> info.latestReleaseVersion = x );
                }

                LOG.debug("update(): latest snapshot (metadata) = "+latestSnapshotVersion);
                LOG.debug("update(): latest release  (metadata) = "+latestReleaseVersion);

                if ( ! versionsWithReleaseDate.isCompleteResult() ) {
                    throw versionsWithReleaseDate.partialResultFailureException();
                }

                info.lastRepositoryUpdate = lastChangeDate;
                info.lastSuccessDate = ZonedDateTime.now();
                info.nextBackgroundUpdate = null;

                if ( StringUtils.isNotBlank( artifact.version ) && info.getVersion( artifact.version ).isEmpty() )
                {
                    LOG.error( "update(): Found no metadata about version '" + artifact.version + "'of  artifact " + info.artifact );
                    return UpdateResult.ARTIFACT_VERSION_NOT_FOUND;
                }
                return UpdateResult.UPDATED;
            } );
        } 
        catch(Exception e) 
        {
            info.lastFailureDate = ZonedDateTime.now();
            if ( e instanceof FileNotFoundException) {
                LOG.warn("getLatestVersion(): Failed to find artifact on server: "+info);
                return UpdateResult.ARTIFACT_UNKNOWN;
            }
            LOG.error("getLatestVersion(): Error while retrieving artifact metadata from server: "+info+": "+e.getMessage(), LOG.isDebugEnabled() ? e : null);
            throw new IOException(e);
        } finally {
        	LOG.debug("Finished retrieving metadata for "+info.artifact);
        }
    }

    /**
     * Sonatype API seems to refuse returning more than 20 results ... we'll have to page through them to get all.
     *
     * @param data {@link Version} instances with an assigned release date
     * @param numArtifactsInResponse the total number of responses the API sent, may be larger than <code>data.size()</code>
     *                               if not every artifact had a 'timestamp' attribute
     */
    private record PartialResult(List<Version> data, int numArtifactsInResponse) {
        private PartialResult
        {
            Validate.notNull( data, "data must not be null" );
            Validate.isTrue( numArtifactsInResponse >= 0 );
        }
    }

    SonatypeRestAPIUrlBuilder newRESTUrlBuilder() {
        return new SonatypeRestAPIUrlBuilder( sonatypeRestApiBaseUrl );
    }

    private PossiblyIncompleteResult queryReleaseDatesUsingHttpHead(Artifact artifact, Set<String> versionNumbers) throws IOException {
        final List<Version> result = new ArrayList<>();
        IOException partialFailureException = null;
        int requestCount = 0;
        for ( final String versionNumber : versionNumbers )
        {
            try
            {
                final Optional<ZonedDateTime> timestamp =
                    getLastModified( artifact.groupId, artifact.artifactId, versionNumber, artifact.classifier, artifact.type );
                requestCount++;
                if ( timestamp.isPresent() ) {
                    result.add( new Version( versionNumber, timestamp.get() ) );
                }
            } catch(IOException e) {
                if ( requestCount == 0 ) {
                    throw e;
                }
                partialFailureException = e;
                break;
            }
        }
        return new PossiblyIncompleteResult( result, partialFailureException );
    }

    private PossiblyIncompleteResult queryReleaseDatesUsingSolr(Artifact artifact, Set<String> versionNumbers) throws IOException {

        if ( versionNumbers.isEmpty() ) {
            return new PossiblyIncompleteResult( new ArrayList<>(0), null );
        }

        final PossiblyIncompleteResult result;
        if ( versionNumbers.size() > 3 ) {
            LOG.trace( "[bulk fetch] Querying release date for {} versions of {}:{}:{}",
                versionNumbers.size(), artifact.groupId, artifact.artifactId, artifact.classifier);
            // too many , do one bulk SOLR query instead of multiple individual queries
            result = queryAllReleaseDates( artifact );
            result.result.removeIf( x -> !versionNumbers.contains( x.versionString ) );
        }
        else
        {
            // perform individual SOLR queries
            LOG.trace( "Querying release date for {} versions of {}:{}:{}",
                versionNumbers.size(), artifact.groupId, artifact.artifactId, artifact.classifier);
            final List<Version> list = new ArrayList<>(versionNumbers.size());
            IOException partialResultFailureException = null;
            for ( final String version : versionNumbers )
            {
                final SonatypeRestAPIUrlBuilder urlBuilder = newRESTUrlBuilder()
                    .groupId( artifact.groupId )
                    .artifactId( artifact.artifactId )
                    .classifier( artifact.classifier )
                    .version( version );
                final URL restApiURL = urlBuilder.build();
                try
                {
                    final PartialResult res = performGET( restApiURL, this::parseSonatypeResponse );
                    list.addAll( res.data() );
                } catch(IOException e) {
                    partialResultFailureException = e;
                    break;
                }
            }
            result = new PossiblyIncompleteResult( list, partialResultFailureException );
        }
        // intentionally assigning all 'Version' instances generated by a single queryAllVersions() call
        // the same 'firstSeenByServer' date (to aid in debugging).
        final ZonedDateTime now = ZonedDateTime.now();
        for ( final Version v : result.result )
        {
            v.firstSeenByServer = now;
        }
        return result;
    }

    /**
     *
     * @param result REST API call results, possibly incomplete
     * @param partialResultFailureException <code>null</code> if no errors happened or the
     *                                      <code>IOException</code> that happened after the first API call
     *                                      succeeded but a subsequent one failed and thus the result is incomplete.
     */
    private record PossiblyIncompleteResult(List<Version> result, IOException partialResultFailureException) {

        public boolean isCompleteResult() {
            return partialResultFailureException == null;
        }
    }

    private PossiblyIncompleteResult queryAllReleaseDates(Artifact artifact) throws IOException
    {
        final SonatypeRestAPIUrlBuilder urlBuilder = newRESTUrlBuilder()
            .groupId( artifact.groupId )
            .artifactId( artifact.artifactId )
            .classifier( artifact.classifier )
            .returnAllResults();

        URL restApiURL = urlBuilder.build();

        LOG.debug("queryAllReleaseDates(): Initial Solr query => {}", restApiURL);

        // need to query in a loop here as the REST API seems to refuse returning more than 20 results at once
        final PartialResult first = performGET( restApiURL, this::parseSonatypeResponse );
        int remaining = first.numArtifactsInResponse() - first.data().size();
        final List<Version> result = new ArrayList<>( first.data() );
        IOException partialResultFailureException = null;
        if ( remaining > 0 ) {

            LOG.debug( "queryAllReleaseDates(): Artifact " + artifact + " has " + first.numArtifactsInResponse() + " releases.");
            int currentPageOffset = first.data().size();
            PartialResult tmp;
            do
            {
                restApiURL = urlBuilder.startOffset( currentPageOffset ).build();

                LOG.debug( "queryAllReleaseDates(): querying next batch from Solr using {}", restApiURL );
                try
                {
                    tmp = performGET( restApiURL, this::parseSonatypeResponse );
                } catch(IOException ex) {
                    partialResultFailureException = ex;
                    break;
                }
                result.addAll( tmp.data() );
                final int resultCount = tmp.data().size();
                currentPageOffset += tmp.numArtifactsInResponse();
                remaining -= resultCount;
            } while (! tmp.data().isEmpty() && remaining > 0 );
        }
        if ( partialResultFailureException == null && result.size() != first.numArtifactsInResponse() ) {
            final String msg = "Tried to retrieve " + first.numArtifactsInResponse() + " versions for " + artifact + " " +
                               "but only got " + result.size();
            LOG.error( "queryAllReleaseDates(): " + msg );
            throw new IOException( msg );
        }
        return new PossiblyIncompleteResult( result, partialResultFailureException );
    }

    private PartialResult parseSonatypeResponse(InputStream stream) throws IOException
    {
        final TypeReference<HashMap<String, Object>> typeRef = new TypeReference<>() {};

        final byte[] data = stream.readAllBytes();
        final String json = new String( data, StandardCharsets.UTF_8 );

        final HashMap<String, Object> map = JSON_MAPPER.readValue( json, typeRef );

            /*
                {
                   "responseHeader":{
                      "status":0,
                      "QTime":2,
                      "params":{
                         "q":"g:de.code-sourcery.versiontracker AND a:versiontracker-enforcerrule AND v:1.0.22",
                         "core":"",
                         "indent":"off",
                         "fl":"id,g,a,v,p,ec,timestamp,tags",
                         "start":"",
                         "sort":"score desc,timestamp desc,g asc,a asc,v desc",
                         "rows":"20",
                         "wt":"json",
                         "version":"2.2"
                      }
                   },
                   "response":{
                      "numFound":1,
                      "start":0,
                      "docs":[
                         {
                            "id":"de.code-sourcery.versiontracker:versiontracker-enforcerrule:1.0.22",
                            "g":"de.code-sourcery.versiontracker",
                            "a":"versiontracker-enforcerrule",
                            "v":"1.0.22",
                            "p":"jar",
                            "timestamp":1714834541000,
                            "ec":[
                               "-sources.jar",
                               ".pom",
                               "-javadoc.jar",
                               ".jar"
                            ]
                         }
                      ]
                   }
                }
             */

        final Map<String,Version> result = new HashMap<>();
        final Map<String, Object> response = (Map<String, Object>) map.get( "response" );
        if ( response == null )
        {
            throw new IOException( "getReleaseDateNew(): JSON response contained no 'response' attribute?" );
        }
        if ( ! response.containsKey( "numFound" ) )
        {
            throw new IOException( "JSON response contained no 'numFound' attribute?" );
        }
        final int numFound = ((Number) (response.get( "numFound" ))).intValue();
        if ( LOG.isTraceEnabled() )
        {
            LOG.trace( "getReleaseDateNew(): Response found " + numFound + " artifacts" );
        }
        final List<Map<String, Object>> docs = (List<Map<String, Object>>) response.get( "docs" );
        for ( final Map<String, Object> artifactDetails : docs )
        {
            if ( artifactDetails.containsKey( "timestamp" ) )
            {
                final long ts = ((Number) (artifactDetails.get( "timestamp" ))).longValue();
                final String version = (String) (artifactDetails.get( "v" ));
                final ZonedDateTime releaseDate = Instant.ofEpochMilli( ts ).atZone( ZoneId.systemDefault() );
                final Version newVersion = new Version( version, releaseDate );

                // we're doing a sloppy Solr search without considering the artifact type so we MIGHT
                // get duplicates in the response...
                final Version existing = result.get( version );
                if ( existing == null || existing.releaseDate.isAfter(  releaseDate ) )
                {
                    result.put( version, newVersion );
                }
            }
        }
        if ( numFound > 0 && result.isEmpty() )
        {
            LOG.warn( "getReleaseDateNew(): JSON response contained " + docs.size() + " artifacts but none had a 'timestamp' attribute?" );
        }
        final List<Version> sortedList = result.values().stream().sorted( Comparator.comparing( a -> a.versionString ) )
            .collect( Collectors.toCollection( ArrayList::new ) );
        return new PartialResult( sortedList, numFound );
    }

    public static Document parseXML(InputStream inputStream) throws IOException
    {
        if ( inputStream == null ) {
            throw new IOException("input stream cannot be NULL");
        }

        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        final StringBuilder xml = logServerResponseOnError() ? inputStreamToString(inputStream) : null;
        try {
            final DocumentBuilder builder = factory.newDocumentBuilder();

            // set fake EntityResolver , otherwise parsing is incredibly slow (~1 sec per file on my i7)
            // because the parser will download the DTD from the internets...
            builder.setEntityResolver( new DummyResolver() );
            if ( logServerResponseOnError() )
            {
                //noinspection DataFlowIssue
                inputStream = new ByteArrayInputStream(xml.toString().getBytes(StandardCharsets.UTF_8));
            }
            return builder.parse(inputStream);
        }
        catch(ParserConfigurationException | SAXException e) 
        {
            LOG.error("parseXML(): Failed to parse document: "+e.getMessage(),LOG.isDebugEnabled() ? e : null);
            if ( logServerResponseOnError() )
            {
                LOG.error("parseXML(): Response from server: "+xml);
            }
            throw new IOException("Failed to parse document: "+e.getMessage(),e);
        }
    }

    private static StringBuilder inputStreamToString(InputStream inputStream) throws IOException
    {
        StringBuilder xml;
        xml = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(inputStream))
        {
            final char[] buffer = new char[1024];
            for (int len; (len = reader.read(buffer)) > 0; )
            {
                xml.append(buffer, 0, len);
            }
        }
        return xml;
    }

    private static boolean logServerResponseOnError() {
        return LOG.isDebugEnabled();
    }

    private static final class DummyResolver implements EntityResolver {

        @Override
        public InputSource resolveEntity(String publicId, String systemId)
        {
            final ByteArrayInputStream dummy = new ByteArrayInputStream(new byte[0]);
            return new InputSource(dummy);
        }
    }

    private CloseableHttpClient getClient(URI uri)
    {
        synchronized(clients) {
            final String key = uri.getScheme()+"_"+uri.getHost()+"_"+uri.getPort();
            CloseableHttpClient client = clients.get(key);
            if ( client==null )
            {
                final DefaultConnectionKeepAliveStrategy defaultKeepAlive =
                    new DefaultConnectionKeepAliveStrategy();
                client = HttpClients.custom()
                    .setKeepAliveStrategy(defaultKeepAlive)
                    .setUserAgent( "MavenCentralVersionProvider/1.0.0 (tobias.gierke@voipfuture.com)" )
                    .setConnectionManager(connManager)
                    .setConnectionManagerShared(true).build();
                    clients.put(key, client);
            }
            return client;
        }
    }

    private FifoRateLimiter getRateLimiter(URI uri)
    {
        synchronized(rateLimiters) {
            final String key = uri.getScheme()+"_"+uri.getHost()+"_"+uri.getPort();
            return rateLimiters.computeIfAbsent(key, u -> new FifoRateLimiter( solrApiRateLimit ));
        }
    }

    private Optional<ZonedDateTime> getLastModified(String groupId, String artifactId, String version, String classifier, String type) throws IOException
    {
        final StringBuilder repo1Url = new StringBuilder(DEFAULT_REPO1_BASE_URL);
        repo1Url.append( groupId.replace('.', '/') );
        repo1Url.append("/").append( artifactId.replace('.', '/') );
        repo1Url.append("/").append( version );

        // versiontracker-common-1.0.23.pom
        repo1Url.append("/").append( artifactId);
        repo1Url.append("-").append( version );
        if ( StringUtils.isNotBlank( classifier ) ) {
            repo1Url.append("-").append( classifier);
        }
        repo1Url.append(".pom");

        final URI uri;
        try {
            uri = new URI(repo1Url.toString());
        }
        catch ( URISyntaxException e ) {
            throw new IOException( "URL is not RFC2396-compliant and cannot be converted into an URI", e);
        }

        // make sure we don't hit Maven central too hard
        try
        {
            if ( ! REPO1_HOST.equals( uri.getHost() ) )
            {
                getRateLimiter( uri ).acquire();
            }
        }
        catch( InterruptedException e )
        {
            throw new InterruptedIOException( "rate-limiter caught InterruptedException" );
        }

        final long start = System.currentTimeMillis();
        final HttpHead httpHead;
        try {
            httpHead = new HttpHead( uri );
        } catch (Exception e1) {
            LOG.debug("getLastModified(): Should not happen: '"+uri+"'",e1);
            throw new RuntimeException(e1);
        }

        synchronized ( statistics ) {
            statistics.apiRequests.update();
        }

        try (CloseableHttpResponse response = getClient( uri ).execute( httpHead ))
        {
            final long elapsedMillis = System.currentTimeMillis() - start;
            final int statusCode = response.getCode();

            synchronized ( statistics ) {
                statistics.httpRequestCountByResponseCode.compute( statusCode, (k,v)-> v == null ? new RequestCount() : ((RequestCount) v).incCount() );
            }
            LOG.debug("getLastModified(): [{} millis] {}",elapsedMillis, repo1Url);
            if ( statusCode != 200 ) {
                LOG.error( "getLastModified(): HTTP request to {} returned {}", uri, response.getReasonPhrase() );
                if ( statusCode == 404 ) {
                    return Optional.empty();
                }
                throw new IOException( "HTTP request to " + uri + " returned " + response.getReasonPhrase() );
            }
            final Header header = response.getFirstHeader("Last-Modified");
            if (header != null && header.getValue() != null) {
                // Parse HTTP date string (RFC 1123 format) into ZonedDateTime
                return Optional.of( ZonedDateTime.parse( header.getValue(), DateTimeFormatter.RFC_1123_DATE_TIME ) );
            }
            throw new IOException("HEAD response without 'Last-Modified' header?");
        }
    }

    private <T> T performGET(URL url2, MyStreamHandler<T> handler) throws IOException
    {
        LOG.debug("performGET(): Connecting to "+url2);

        URI uri;
        try {
            uri = url2.toURI();
        }
        catch ( URISyntaxException e ) {
            throw new IOException( "URL is not RFC2396-compliant and cannot be converted into an URI", e);
        }

        // make sure we don't hit Solr too hard
        try
        {
            if ( ! REPO1_HOST.equals( uri.getHost() ) )
            {
                getRateLimiter( uri ).acquire();
            }
        }
        catch( InterruptedException e )
        {
            throw new InterruptedIOException( "rate-limiter caught InterruptedException" );
        }

        final long start = System.currentTimeMillis();
        final HttpGet httpget;
        try {
            httpget = new HttpGet( uri );
        } catch (Exception e1) {
            LOG.debug("performGET(): Should not happen: '"+uri+"'",e1);
            throw new RuntimeException(e1);
        }

        synchronized ( statistics ) {
            statistics.apiRequests.update();
        }

        try (CloseableHttpResponse response = getClient( uri ).execute( httpget )) {
            final int statusCode = response.getCode();

            synchronized ( statistics ) {
                statistics.httpRequestCountByResponseCode.compute( statusCode, (k,v)-> v == null ? new RequestCount() : ((RequestCount) v).incCount() );
            }

            if ( statusCode != 200 ) {
                LOG.error( "performGET(): HTTP request to " + uri + " returned " + response.getReasonPhrase() );
                if ( statusCode == 404 ) {
                    throw new FileNotFoundException( "(HTTP 404) Failed to find " + uri );
                }
                throw new IOException( "HTTP request to " + uri + " returned " + response.getReasonPhrase() );
            }
            try ( final HttpEntity entity = response.getEntity() ) {
                try ( InputStream instream = entity.getContent() ) {
                    LOG.debug( "performGET(): Got Input Stream after " + (System.currentTimeMillis() - start) + " ms" );
                    return handler.process( new TeeInputStream(instream, System.out) );
                }
                finally {
                    LOG.debug( "performGET(): Finished processing after " + (System.currentTimeMillis() - start) + " ms" );
                }
            }
        }
    }

    private String readString(XPathExpression expression,Document document) throws IOException
    {
        try {
            return expression.evaluate( document );
        }
        catch(Exception e) {
            if ( LOG.isDebugEnabled() ) {
                LOG.error("parseXML(): Failed to parse document: "+e.getMessage(),e);
            } else {
                LOG.error("parseXML(): Failed to parse document: "+e.getMessage());
            }
            throw new IOException("Failed to parse document: "+e.getMessage(),e);
        }
    }

    private List<String> readStrings(XPathExpression expr,Document doc) throws IOException
    {
        try {
            final NodeList nodes = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);
            final List<String> versions = new ArrayList<>(nodes.getLength());
            for (int i = 0, l = nodes.getLength(); i < l ; i++) {
                versions.add(nodes.item(i).getNodeValue());
            }
            return versions;
        }
        catch(Exception e) {
            if ( LOG.isDebugEnabled() ) {
                LOG.error("parseXML(): Failed to parse document: "+e.getMessage(),e);
            } else {
                LOG.error("parseXML(): Failed to parse document: "+e.getMessage());
            }
            throw new IOException("Failed to parse document: "+e.getMessage(),e);
        }
    }

    static String metaDataPath(Artifact artifact) {
        return artifact.groupId.replace('.','/')+"/"+artifact.artifactId+"/maven-metadata.xml";
    }

    @Override
    public Statistics getStatistics() {
        synchronized ( statistics ) {
            return statistics.createCopy();
        }
    }

    @Override
    public void resetStatistics()
    {
        synchronized( statistics ) {
            statistics.reset();
        }
    }

    public void setSolrApiRateLimit(RateLimit rateLimit)
    {
        Validate.notNull( rateLimit, "rateLimit must not be null" );
        LOG.info("setSolrApiRateLimit(): Using Solr API rate limit "+rateLimit);
        this.solrApiRateLimit = rateLimit;
    }
}