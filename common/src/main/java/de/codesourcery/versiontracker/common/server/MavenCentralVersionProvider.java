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

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final DateTimeFormatter MAVEN_REPO_INDEX_DATE_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("UTC"));

    public static final String DEFAULT_MAVEN_URL = "https://repo1.maven.org/maven2/";


    /*
     * <a href="junit-4.12-javadoc.jar" title="junit-4.12-javadoc.jar">junit-4.12-javadoc.jar</a>
     *                             2014-12-04 16:17    937942
     */
    private static final Pattern LINE_PATTERN = Pattern.compile("<a .*?>(.*?)</a>\\s*(\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2})\\s+(\\d+)");

    private static final class MyExpressions // XPathExpression is NOT thread-safe to we use a ThreadLocal + this wrapper
    {
        private final XPathExpression latestSnapshot;
        private final XPathExpression latestRelease;
        private final XPathExpression lastUpdateDate;
        private final XPathExpression versionsXPath;

        public MyExpressions()
        {
            final XPathFactory factory = XPathFactory.newInstance();
            final XPath xpath = factory.newXPath();

            try {
                latestSnapshot = xpath.compile("/metadata/versioning/latest[text()]");
                latestRelease = xpath.compile("/metadata/versioning/release[text()]");
                lastUpdateDate = xpath.compile("/metadata/versioning/lastUpdated");
                versionsXPath = xpath.compile("/metadata/versioning/versions/version");
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
    private String serverBase;
    private final ThreadLocal<MyExpressions> expressions = ThreadLocal.withInitial( MyExpressions::new );
    private final Map<URL,HttpClient> clients = new HashMap<>();

    private int maxConcurrentThreads = 10;

    // @GuardedBy( statistics )
    private final Statistics statistics = new Statistics();

    private final Object THREAD_POOL_LOCK=new Object();
    private ThreadPoolExecutor threadPool;

    public MavenCentralVersionProvider()
    {
        this(DEFAULT_MAVEN_URL);
        connManager.setDefaultMaxPerRoute(10);
        connManager.setMaxTotal(20);
    }

    public MavenCentralVersionProvider(String serverBase) 
    {
        this.serverBase = serverBase+(serverBase.trim().endsWith("/") ? "" : "/" );
    }
    
    public static void main(String[] args) throws IOException
    {
        final Artifact test = new Artifact();
        test.groupId = "commons-lang";
        test.artifactId = "commons-lang";

        VersionInfo data = new VersionInfo();
        data.artifact = test;
        long start = System.currentTimeMillis();
        final UpdateResult result = new MavenCentralVersionProvider().update( data, Collections.emptySet() );
        long end = System.currentTimeMillis();
        System.out.println("TIME: "+(end-start)+" ms");
        System.out.println("RESULT: "+result);
        System.out.println("GOT: "+data);
    }

    @Override
    public UpdateResult update(VersionInfo info, Set<String> additionalVersionsToFetchReleaseDatesFor) throws IOException
    {
        final Artifact artifact = info.artifact;
        final URL url = new URL( serverBase+metaDataPath( artifact ) );
        
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("update(): Retrieving metadata for "+info.artifact+" from "+url);
        }
        
        try 
        {
            return performGET(url, stream -> {
                synchronized ( statistics ) {
                    statistics.metaDataRequests.update();
                }
                final Document document = parseXML( stream );

                // note: XPath evaluation is not thread-safe so we have to use a ThreadLocal here
                final MyExpressions expr = expressions.get();
                final Set<String> versionFromMetaData = new HashSet<>( readStrings( expr.versionsXPath , document ) );
                for ( String version : versionFromMetaData ) {
                    info.maybeAddVersion( new Version(version,null) );
                }

                for (Iterator<Version> it = info.versions.iterator(); it.hasNext();) {
                    final Version v = it.next();
                    if ( ! versionFromMetaData.contains( v.versionString ) ) {
                        LOG.warn("update(): Version "+v+" is gone from metadata.xml of "+info.artifact+" ?");
                        it.remove();
                    }
                }

                // gather version numbers for which we do not know the release date yet
                final Set<String> versionsToRequest = new HashSet<>();
                additionalVersionsToFetchReleaseDatesFor.stream()
                    .filter( x -> info.getVersion( x ).isPresent() ).forEach( versionsToRequest::add );

                if ( StringUtils.isNotBlank(artifact.version) )
                {
                    if ( info.getVersion( artifact.version).isEmpty() )
                    {
                        info.lastFailureDate = ZonedDateTime.now();
                        LOG.error("update(): metadata xml contained no version '"+artifact.version+"' for artifact "+info.artifact);
                        return UpdateResult.ARTIFACT_VERSION_NOT_FOUND;
                    }
                }

                // parse latest snapshot & release versions from metadata
                String latestSnapshotVersion = readString(expr.latestSnapshot, document );
                String latestReleaseVersion = readString(expr.latestRelease, document );

                LOG.debug("update(): latest snapshot (metadata) = "+latestSnapshotVersion);
                LOG.debug("update(): latest release  (metadata) = "+latestReleaseVersion);

                if ( StringUtils.isNotBlank(latestSnapshotVersion) ) {
                    versionsToRequest.add(latestSnapshotVersion);
                }
                if ( StringUtils.isNotBlank(latestReleaseVersion) ) {
                    versionsToRequest.add(latestReleaseVersion);
                }

                // last repository change date
                final String lastChangeString = readString(expr.lastUpdateDate, document );
                LOG.debug("update(): last repository change = "+lastChangeString);

                final ZonedDateTime lastChangeDate = ZonedDateTime.parse( lastChangeString, DATE_FORMATTER);
                if ( info.lastRepositoryUpdate != null && info.lastRepositoryUpdate.equals( lastChangeDate ) )
                {
                    info.lastSuccessDate = ZonedDateTime.now();
                    return UpdateResult.NO_CHANGES_ON_SERVER;
                }
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug( "update(): Repository changed on server" );
                    LOG.debug( "update(): Gathering release dates for versions " + versionsToRequest + "..." );
                }

                if ( ! versionsToRequest.isEmpty() )
                {
                    final Map<String,Version> result = getReleaseDates(artifact,versionsToRequest);
                    for ( Map.Entry<String,Version> entry : result.entrySet() )
                    {
                        final Optional<Version> existing = info.getVersion( entry.getKey() );
                        if ( existing.isPresent() )
                        {
                            existing.get().releaseDate = entry.getValue().releaseDate;
                            LOG.debug("update(): Updated existing version to "+existing.get());
                        } else {
                            final Version newVersion = entry.getValue();
                            info.versions.add( newVersion );
                            LOG.debug("update(): Adding NEW version "+ newVersion );
                        }
                    }
                }
                info.artifact = artifact;
                if ( StringUtils.isNotBlank( latestReleaseVersion ) ) {
                    info.getVersion( latestReleaseVersion ).ifPresent( x -> info.latestReleaseVersion = x );
                }
                if ( StringUtils.isNotBlank( latestSnapshotVersion ) ) {
                    info.getVersion( latestSnapshotVersion ).ifPresent( x -> info.latestSnapshotVersion = x );
                }
                info.lastRepositoryUpdate = lastChangeDate;
                info.lastSuccessDate = ZonedDateTime.now();
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
    
    private Map<String,Version> getReleaseDates(Artifact artifact, Set<String> versionNumbers)
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
                    final Optional<Version> v = getReleaseDate(artifact,versionNumber);
                    if ( v.isPresent() ) {
                        synchronized(result) {
                            result.put(versionNumber,v.get());
                        }
                    }
                } catch(Exception e) {
                    LOG.error("readVersion(): Failed to retrieve version '"+versionNumber+"' for "+artifact);
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

    private Optional<Version> getReleaseDate(Artifact artifact, String versionString) throws IOException
    {
        Validate.notBlank(versionString, "versionString must not be NULL/blank");

        final URL url2 = new URL( serverBase+getPathToFolder( artifact, versionString ) );        
        LOG.debug("readVersion(): Looking for release date of version '"+versionString+"' for "+artifact);
        
        return performGET(url2, stream -> {

            synchronized ( statistics ) {
                statistics.releaseDateRequests.update();
            }

            final String page = String.join( "\n", IOUtils.readLines( stream, StandardCharsets.UTF_8 ) );
            Matcher m = LINE_PATTERN.matcher( page );

            ZonedDateTime latest = null;
            final boolean trace = LOG.isTraceEnabled();
            while ( m.find() )
            {
                final String filename = m.group(1);
                final String uploadDate = m.group(2);
                final String size = m.group(3);
                if ( filename.trim().endsWith(".jar" ) )
                {
                    final ZonedDateTime date = ZonedDateTime.parse( uploadDate, MAVEN_REPO_INDEX_DATE_FORMATTER );
                    if( latest == null || latest.compareTo( date ) < 0 ) {
                        latest = date;
                    }
                    if ( trace ) {
                        LOG.trace( "readVersion(): (*) FOUND: "+filename + " | " + uploadDate + " | " + size);
                    }
                } else {
                    if ( trace ) {
                        LOG.trace( "readVersion(): FOUND: "+filename + " | " + uploadDate + " | " + size);
                    }
                }
            }
            return latest == null ? Optional.empty() : Optional.of( new Version(versionString,latest) );
        } );
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

    private HttpClient getClient(URL url)
    {
        synchronized(clients) {
            HttpClient client = clients.get(url);
            if ( client==null )
            {
                final DefaultConnectionKeepAliveStrategy defaultKeepAlive = new  DefaultConnectionKeepAliveStrategy();
                client = HttpClients.custom()
                    .setKeepAliveStrategy(defaultKeepAlive)
                    .setConnectionManager(connManager)
                    .setConnectionManagerShared(true).build();
                clients.put(url, client);
            }
            return client;
        }
    }

    private <T> T performGET(URL url, MyStreamHandler<T> handler) throws IOException
    {
        LOG.debug("performGET(): Connecting to "+url);

        final long start = System.currentTimeMillis();
        final HttpGet httpget;
        try {
            httpget = new HttpGet( url.toURI() );
        } catch (Exception e1) {
            LOG.debug("performGET(): Should not happen: '"+url+"'",e1);
            throw new RuntimeException(e1);
        }
        final HttpResponse response = getClient(url).execute(httpget);
        final int statusCode = response.getStatusLine().getStatusCode();
        if ( statusCode != 200 ) {
            LOG.error( "performGET(): HTTP request to " + url + " returned " + response.getStatusLine() );
            if ( statusCode == 404 ) {
                throw new FileNotFoundException( "Failed to find " + url );
            }
            throw new IOException( "HTTP request to " + url + " returned " + response.getStatusLine() );
        }
        final HttpEntity entity = response.getEntity();
        try (InputStream instream = entity.getContent() )
        {
            LOG.debug("performGET(): Got Input Stream after "+(System.currentTimeMillis()-start)+" ms");
            return handler.process( instream );
        }
        finally
        {
            LOG.debug("performGET(): Finished processing after "+(System.currentTimeMillis()-start)+" ms");
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
}