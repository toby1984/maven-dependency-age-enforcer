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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
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

    public static final String DEFAULT_REPO1_BASE_URL = "https://repo1.maven.org/maven2/";
    public static final String DEFAULT_SONATYPE_REST_API_BASE_URL = "https://search.maven.org/solrsearch/select";

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

        public MyExpressions()
        {
            final XPathFactory factory = XPathFactory.newInstance();
            final XPath xpath = factory.newXPath();

            try {
                latestSnapshot = xpath.compile("/metadata/versioning/latest[text()]");
                latestRelease = xpath.compile("/metadata/versioning/release[text()]");
                lastUpdateDate = xpath.compile("/metadata/versioning/lastUpdated");
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
    private final Map<URI,CloseableHttpClient> clients = new HashMap<>();

    private int maxConcurrentThreads = 10;

    // @GuardedBy( statistics )
    private final Statistics statistics = new Statistics();

    private final Object THREAD_POOL_LOCK=new Object();
    private ThreadPoolExecutor threadPool;

    private ConfigurationProvider configurationProvider;

    public MavenCentralVersionProvider()
    {
        this( DEFAULT_REPO1_BASE_URL, DEFAULT_SONATYPE_REST_API_BASE_URL);
        connManager.setDefaultMaxPerRoute(10);
        connManager.setMaxTotal(20);
    }

    public MavenCentralVersionProvider(String repo1BaseUrl, String sonatypeRestApiBaseUrl)
    {
        this.repo1BaseUrl = repo1BaseUrl+(repo1BaseUrl.trim().endsWith("/") ? "" : "/" );
        this.sonatypeRestApiBaseUrl = sonatypeRestApiBaseUrl+(sonatypeRestApiBaseUrl.trim().endsWith("/") ? "" : "/" );
    }

    @Override
    public void setConfigurationProvider(ConfigurationProvider configurationProvider)
    {
        Validate.notNull( configurationProvider, "configurationProvider must not be null" );
        this.configurationProvider = configurationProvider;
    }

    public static void main(String[] args) throws IOException
    {
        final Artifact test = new Artifact();
        test.groupId = "org.apache.tomcat";
        test.artifactId = "tomcat";

        VersionInfo data = new VersionInfo();
        data.artifact = test;
        long start = System.currentTimeMillis();
        final UpdateResult result = new MavenCentralVersionProvider().update( data, false );
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

                // get all versions
                final List<Version> allVersions = queryAllVersions( info.artifact );
                info.removeVersionsIf( v -> {
                    final boolean remove = allVersions.stream().noneMatch( x -> x.versionString.equals( v.versionString ) );
                    if ( remove ) {
                        LOG.warn("update(): Version "+v+" is gone from metadata.xml of "+info.artifact+" ?");
                    }
                    return remove;
                } );
                allVersions.forEach( info::addVersion );

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

                info.lastRepositoryUpdate = lastChangeDate;
                info.lastSuccessDate = ZonedDateTime.now();

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

    private final ThreadFactory threadFactory = new ThreadFactory() {
        
        private final ThreadGroup threadGroup = new ThreadGroup(Thread.currentThread().getThreadGroup(),"releasedate-request-threads" );
        private final AtomicInteger threadId = new AtomicInteger(0); 
        public Thread newThread(Runnable r) 
        {
            final Thread t = new Thread(threadGroup,r);
            t.setDaemon( true );
            t.setName("releasedate-request-thread-"+threadId.incrementAndGet());
            return t;
        }
    };
    
    private void submit(Runnable r) 
    {
        synchronized( THREAD_POOL_LOCK ) 
        {
            if ( threadPool == null ) 
            {
                LOG.info("setMaxConcurrentThreads(): Using "+maxConcurrentThreads+" threads to retrieve artifact metadata.");                
                final BlockingQueue<Runnable> workingQueue = new ArrayBlockingQueue<>(200);
                threadPool = new ThreadPoolExecutor( maxConcurrentThreads, maxConcurrentThreads, 60 , TimeUnit.SECONDS,
                        workingQueue,threadFactory, new ThreadPoolExecutor.CallerRunsPolicy() );
            }
            threadPool.submit( r );
        }
    }
    
    private Map<String,Version> queryReleaseDates(Artifact artifact, Set<String> versionNumbers)
    {
        final Map<String,Version> result = new HashMap<>();
        if ( versionNumbers.isEmpty() ) {
            return result;
        }
        final CountDownLatch latch = new CountDownLatch( versionNumbers.size() );
        for ( String versionNumber : versionNumbers )
        {
            // to stuff
            final Runnable r = () -> 
            {
                try {
                    final Optional<ZonedDateTime> v = queryReleaseDate(artifact,versionNumber);
                    if ( v.isPresent() ) {
                        synchronized(result) {
                            result.put(versionNumber,new Version(versionNumber,v.get()) );
                        }
                    }
                } catch(Exception e) {
                    LOG.error("readVersion(): Failed to retrieve version '"+versionNumber+"' for "+artifact,e);
                } finally {
                    latch.countDown();
                }
            };
            submit(r);
        }
        
        while ( true ) 
        {
            try {
                if ( latch.await(10,TimeUnit.SECONDS) ) {
                    return result;
                }
            } catch(InterruptedException e) { /* can't help it */ }
            LOG.debug("readVersions(): Still waiting for "+latch.getCount()+" outstanding requests of artifact "+artifact);
        }
    }

    private Optional<ZonedDateTime> queryReleaseDate(Artifact artifact, String versionString) throws IOException {

        final URL url = newRESTUrlBuilder()
            .groupId( artifact.groupId )
            .artifactId( artifact.artifactId )
            .version( versionString )
            .classifier( artifact.classifier ).build();
        return performGET( url, stream -> {
            final PartialResult result = parseSonatypeResponse( stream );
            return result.getFirstResult().filter( Version::hasReleaseDate ).map( x -> x.releaseDate );
        } );
    }

    /** Sonatype API seems to refuse returning more than 20 results ... we'll have to page through them to get all ... */
    private record PartialResult(List<Version> data, int totalResultSize) {
        private PartialResult
        {
            Validate.notNull( data, "data must not be null" );
            Validate.isTrue( totalResultSize >= 0 );
        }

        public Optional<Version> getFirstResult()
        {
            return data.isEmpty() ? Optional.empty() : Optional.of( data.get(0) );
        }
    }

    SonatypeRestAPIUrlBuilder newRESTUrlBuilder() {
        return new SonatypeRestAPIUrlBuilder( sonatypeRestApiBaseUrl );
    }

    // code left here because it was a PITA to write and it might come in handy during debugging when
    // comparing the Maven indexer XML vs. the real deal
    private List<Version> queryAllVersions(Artifact artifact) throws IOException
    {
        final SonatypeRestAPIUrlBuilder urlBuilder = newRESTUrlBuilder()
            .groupId( artifact.groupId )
            .artifactId( artifact.artifactId )
            .classifier( artifact.classifier )
            .returnAllResults();

        URL restApiURL = urlBuilder.build();

        // need to query in a loop here as the REST API seems to refuse returning more than 20 results
        // at once
        final PartialResult first = performGET( restApiURL, this::parseSonatypeResponse );
        int remaining = first.totalResultSize() - first.data().size();
        final List<Version> result = new ArrayList<>( first.data() );
        if ( remaining > 0 ) {

            LOG.debug("queryAllVersions(): Artifact "+artifact+" has "+first.totalResultSize()+" releases.");
            int offset = first.data().size();
            PartialResult tmp;
            do
            {
                restApiURL = urlBuilder.startOffset( offset ).build();

                tmp = performGET( restApiURL, this::parseSonatypeResponse );
                result.addAll( tmp.data() );
                final int resultCount = tmp.data().size();
                offset += resultCount;
                remaining -= resultCount;
            } while (! tmp.data().isEmpty() && remaining > 0 );
        }
        if ( result.size() != first.totalResultSize() ) {
            final String msg = "Tried to retrieve " + first.totalResultSize() + " versions for " + artifact + " " +
                "but only got " + result.size();
            LOG.error( "queryAllVersions(): " + msg );
            throw new IOException( msg );
        }
        return result;
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

        final List<Version> result = new ArrayList<>();
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
                result.add( new Version( version, Instant.ofEpochMilli( ts ).atZone( ZoneId.systemDefault() ) ) );
            }
        }
        if ( numFound > 0 && result.isEmpty() )
        {
            LOG.warn( "getReleaseDateNew(): JSON response contained " + docs.size() + " artifacts but none had a 'timestamp' attribute?" );
        }
        return new PartialResult( result, numFound );
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
            CloseableHttpClient client = clients.get(uri);
            if ( client==null )
            {
                final DefaultConnectionKeepAliveStrategy defaultKeepAlive = new  DefaultConnectionKeepAliveStrategy();
                client = HttpClients.custom()
                    .setKeepAliveStrategy(defaultKeepAlive)
                    .setConnectionManager(connManager)
                    .setConnectionManagerShared(true).build();
                    clients.put(uri, client);
            }
            return client;
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

        final long start = System.currentTimeMillis();
        final HttpGet httpget;
        try {
            httpget = new HttpGet( uri );
        } catch (Exception e1) {
            LOG.debug("performGET(): Should not happen: '"+uri+"'",e1);
            throw new RuntimeException(e1);
        }
        try (CloseableHttpResponse response = getClient( uri ).execute( httpget )) {
            final int statusCode = response.getCode();
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
                    return handler.process( instream );
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

    private List<String> readStrings(XPathExpression expression,Document document) throws IOException
    {
        try
        {
            final NodeList nodeList = (NodeList) expression.evaluate( document ,  XPathConstants.NODESET );
            final int len = nodeList.getLength();
            final List<String> result = new ArrayList<>(len);
            for ( int i = 0 ; i < len ; i++ )
            {
                final Node n = nodeList.item( i );
                final String versionString = n.getTextContent();
                result.add( versionString );
            }
            return result;
        }
        catch(Exception e) {
            LOG.error("parseXML(): Failed to parse document: "+e.getMessage(),e);
            throw new IOException("Failed to parse document: "+e.getMessage(),e);
        }
    }

    static String metaDataPath(Artifact artifact) {
        return artifact.groupId.replace('.','/')+"/"+artifact.artifactId+"/maven-metadata.xml";
    }

    static String getPathToFolder(Artifact artifact,String versionNumber) {
        return  artifact.groupId.replace('.','/')+"/"+artifact.artifactId+"/"+versionNumber+"/";
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
}