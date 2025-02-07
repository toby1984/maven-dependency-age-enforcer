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
package de.codesourcery.versiontracker.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.codesourcery.versiontracker.client.api.IAPIClient.Protocol;
import de.codesourcery.versiontracker.common.APIRequest;
import de.codesourcery.versiontracker.common.APIResponse;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.ArtifactResponse.UpdateAvailable;
import de.codesourcery.versiontracker.common.BinarySerializer;
import de.codesourcery.versiontracker.common.BinarySerializer.IBuffer;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.JSONHelper;
import de.codesourcery.versiontracker.common.QueryRequest;
import de.codesourcery.versiontracker.common.QueryResponse;
import de.codesourcery.versiontracker.common.RequestsPerHour;
import de.codesourcery.versiontracker.common.ServerVersion;
import de.codesourcery.versiontracker.common.Utils;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;
import de.codesourcery.versiontracker.common.server.APIImpl;
import de.codesourcery.versiontracker.common.server.IBackgroundUpdater;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Servlet responsible for processing {@link APIRequest}s.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class APIServlet extends HttpServlet
{
    private static final Logger LOG = LogManager.getLogger(APIServlet.class);

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{(.*?)}");

    private static final ObjectMapper JSON_MAPPER =  JSONHelper.newObjectMapper();

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss" );

    private final RequestsPerHour requestsPerHour = new RequestsPerHour();

    private record StatusInformation(RequestsPerHour httpStats, String applicationVersion, String gitCommit, IVersionStorage.StorageStatistics storageStatistics) {}

    private boolean artifactUpdatesEnabled = true;

    public APIServlet() {
        LOG.info("APIServlet(): Instance created");
    }

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        if ( LOG.isInfoEnabled() ) {
            LOG.debug("service(): Incoming request from "+ req.getRemoteAddr());
        }
        final long start = System.currentTimeMillis();
        try {
            super.service(req, res);
        } 
        catch(RuntimeException e) {
            LOG.error("service(): Uncaught exception",e);
            throw e;
        }
        finally 
        {
            if ( LOG.isInfoEnabled() ) {
                final long elapsed = System.currentTimeMillis() - start;
                LOG.info("service(): Request finished after "+elapsed+" ms");
            }
        }
    }

    private static String toString(ZonedDateTime dt) {
        return dt.format( formatter )+" UTC";
    }

    private void sendFileFromClasspath(String classpathLocation, HttpServletResponse resp, String contentType) throws IOException {
        sendFileFromClasspath( classpathLocation, resp, contentType, null );
    }

    private void sendFileFromClasspath(String classpathLocation, HttpServletResponse resp,
                                       String contentType, Map<String,String> placeholderValues) throws IOException
    {
        final InputStream in = APIServlet.class.getResourceAsStream( classpathLocation );
        if ( in == null ) {
            LOG.error( "sendFileFromClasspath(): File not found - " + classpathLocation + " (" + contentType + ")" );
            resp.sendError( 404 );
            return;
        }

        resp.setContentType( contentType );
        try ( in ) {
            String input = new String( in.readAllBytes(), StandardCharsets.UTF_8 );
            if ( placeholderValues != null && ! placeholderValues.isEmpty() ) {
                input = resolvePlaceholders( input, placeholderValues );
            }
            resp.getWriter().write( input );
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        synchronized ( requestsPerHour ) {
            requestsPerHour.update();
        }

        resp.setHeader( "Cache-Control", "no-cache, no-store, must-revalidate" );

        final String uri = req.getRequestURI();
        if ( uri.contains("/scripts/" ) ) {
            final int idx = uri.indexOf("/scripts/");
            final String scriptName = uri.substring( idx + "/scripts/".length() );
            final String classpathLocation = "/scripts/" + scriptName;
            sendFileFromClasspath( classpathLocation, resp, "text/javascript;charset=UTF-8" );
            return;
        }
        if ( uri.contains("/css/" ) ) {
            final int idx = uri.indexOf("/css/");
            final String scriptName = uri.substring( idx + "/css/".length() );
            final String classpathLocation = "/css/" + scriptName;
            sendFileFromClasspath( classpathLocation, resp, "text/css;charset=UTF-8" );
            return;
        }

        final APIImpl impl = APIImplHolder.getInstance().getImpl();
        final IVersionStorage storage = impl.getVersionTracker().getStorage();

        if ( uri.endsWith("/autocomplete" ) ) {
            final String kind = req.getParameter( "kind" );
            final String userInput = req.getParameter( "userInput" );
            final String[] choices = switch ( kind ) {
                case "groupId" -> {
                    yield storage.getAllVersions().stream()
                        .filter( x -> x.artifact.groupId.contains( userInput ) )
                        .map( x -> x.artifact.groupId )
                        .sorted()
                        .distinct()
                        .limit( 10 ).toArray( String[]::new );
                }
                case "artifactId" -> {
                    final String groupId = req.getParameter( "groupId" );
                    final Predicate<VersionInfo> pred;
                    if ( userInput == null || userInput.isBlank() ) {
                        pred = x -> true;
                    } else {
                        pred = x -> x.artifact.artifactId.contains( userInput );
                    }
                    yield storage.getAllVersions().stream()
                        .filter( x -> x.artifact.groupId.equals( groupId ) )
                        .filter( pred )
                        .map( x -> x.artifact.artifactId )
                        .sorted()
                        .distinct()
                        .limit( 10 ).toArray( String[]::new );
                }
                default -> throw new RuntimeException( "Unknown auto completion type" );
            };
            final String json = JSON_MAPPER.writeValueAsString( choices );
            resp.setContentType( "application/json" );
            resp.getWriter().write( json );
            return;
        }

        final boolean triggerRefresh = uri.endsWith( "/triggerRefresh" );
        if ( uri.endsWith( "/simplequery") || triggerRefresh )
        {
            final Artifact a = new Artifact();
            a.artifactId = req.getParameter( "artifactId" );
            if ( StringUtils.isBlank( a.artifactId ) ) {
                resp.sendError( 500 , "Missing artifactId request parameter" );
                return;
            }
            a.groupId = req.getParameter( "groupId" );
            if ( StringUtils.isBlank( a.groupId ) ) {
                resp.sendError( 500 , "Missing groupId request parameter" );
                return;
            }
            a.setClassifier( req.getParameter( "classifier" ) );
            a.type = req.getParameter( "type" );
            if ( a.type == null) {
                a.type = "jar";
            }

            if ( triggerRefresh ) {
                a.version = req.getParameter( "version" );
                final BiPredicate<VersionInfo,Artifact> requiresUpdate;
                if ( artifactUpdatesEnabled ) {
                    final IBackgroundUpdater updater = impl.getBackgroundUpdater();
                    requiresUpdate = updater::requiresUpdate;
                } else {
                    requiresUpdate = (optVersionInfo,art) -> false;
                }
                try
                {
                    if ( StringUtils.isNotBlank( a.version ) )
                    {
                        impl.getVersionTracker().getVersionInfo( List.of( a ), requiresUpdate );
                    } else {
                        impl.getVersionTracker().forceUpdate( a.groupId, a.artifactId );
                    }
                }
                catch( Exception e )
                {
                    LOG.error( "Caught exception while trying to force version update for "+a, e );
                    resp.sendError( 500, "Version update failed for "+a );
                }
                return;
            }

            final List<VersionInfo> result = new ArrayList<>();
            if ( req.getParameter( "regex" ) != null ) {
                result.addAll( storage.getAllVersions( a.groupId, a.artifactId ) );
                result.removeIf( toCheck -> {
                   if ( ! Objects.equals( toCheck.artifact.type, a.type ) ) {
                       return true;
                   }
                    return a.getClassifier() != null && ! Objects.equals( toCheck.artifact.getClassifier(), a.getClassifier() );
                });
            } else {
                storage.getVersionInfo( a ).ifPresent( result::add );
            }
            resp.setContentType( "application/json" );
            resp.getWriter().write( JSON_MAPPER.writeValueAsString( result ) );
            return;
        }

        if ( uri.endsWith( "/resetstatistics"))
        {
            LOG.info("doGet(): Resetting statistics");
            storage.resetStatistics();
            impl.getVersionTracker().getVersionProvider().resetStatistics();
            impl.getBackgroundUpdater().resetStatistics();
            redirectToHomePage( req, resp );
            return;
        }

        final StatusInformation storageStats = new StatusInformation( requestsPerHour.createCopy(), getApplicationVersion().orElse( null ),
            getSHA1Hash().orElse(null),
            storage.getStatistics()
            );

        final String queryString = req.getQueryString();
        if ( "json".equalsIgnoreCase( queryString ) ) {
            // JSON response
            final String json = JSON_MAPPER.writeValueAsString( storageStats );
            resp.setContentType( "application/json" );
            resp.getWriter().write( json );
        } else {
            // HTML response
                final String rowFragment = """
                <div class="row">
                  <div class="cellName">%s</div><div class="cellValue">%s</div>
                </div>
                    """;

            final StringBuilder fragments = new StringBuilder();
            final Consumer<Object> appender = toAppend -> fragments.append( toAppend ).append( "\n" );
            final BiConsumer<String, Object> keyValue = (k, v) -> appender.accept( rowFragment.formatted( k, Objects.toString( v ) ) );

            keyValue.accept( "Total artifacts",storageStats.storageStatistics.totalArtifactCount );
            keyValue.accept( "Total versions", storageStats.storageStatistics.totalVersionCount );
            keyValue.accept( "Last statistics reset", APIServlet.toString(  storageStats.storageStatistics().lastStatisticsReset ) );

            float sizeInMB = storageStats.storageStatistics.storageSizeInBytes/(1024*1024.0f);
            keyValue.accept( "On-disk storage (MB)", new DecimalFormat("######0.0#").format( sizeInMB ) );

            keyValue.accept( "HTTP requests (current hour)", storageStats.httpStats.getCountForCurrentHour() );
            keyValue.accept( "HTTP requests (last 24 hours)", storageStats.httpStats.getCountForLast24Hours() );

            keyValue.accept( "Last meta-data fetch success", storageStats.storageStatistics().mostRecentSuccess().map( APIServlet::toString).orElse("n/a"));
            keyValue.accept( "Last meta-data fetch failure", storageStats.storageStatistics().mostRecentFailure().map( APIServlet::toString).orElse("n/a"));
            keyValue.accept( "Last meta-data fetch requested", storageStats.storageStatistics().mostRecentRequested().map( APIServlet::toString).orElse("n/a"));

            keyValue.accept( "Storage item reads (most recent)", storageStats.storageStatistics().reads.getMostRecentAccess().map( APIServlet::toString ).orElse( "n/a" ) );
            keyValue.accept( "Storage item reads (current hour)", storageStats.storageStatistics().reads.getCountForCurrentHour());
            keyValue.accept( "Storage item reads (last 24h)", storageStats.storageStatistics().reads.getCountForLast24Hours());

            keyValue.accept( "Storage item writes (most recent)", storageStats.storageStatistics().writes.getMostRecentAccess().map( APIServlet::toString).orElse("n/a"));
            keyValue.accept( "Storage item writes (current hour)", storageStats.storageStatistics().writes.getCountForCurrentHour()+"\n");
            keyValue.accept( "Storage item writes (last 24h)", storageStats.storageStatistics().writes.getCountForLast24Hours()+"\n");

            final IVersionProvider.Statistics repoStats = impl.getVersionTracker().getVersionProvider().getStatistics();

            keyValue.accept( "Repo metadata fetch (most recent)", repoStats.metaDataRequests.getMostRecentAccess().map( APIServlet::toString).orElse("n/a"));
            keyValue.accept( "Repo metadata fetches (current hour)", repoStats.metaDataRequests.getCountForCurrentHour()+"\n");
            keyValue.accept( "Repo metadata fetches (last 24h)", repoStats.metaDataRequests.getCountForLast24Hours()+"\n");

            final IBackgroundUpdater.Statistics bgStats = impl.getBackgroundUpdater().getStatistics();
            keyValue.accept( "Background update (most recent)", bgStats.scheduledUpdates.getMostRecentAccess().map( APIServlet::toString).orElse("n/a"));
            keyValue.accept( "Background updates (current hour)", bgStats.scheduledUpdates.getCountForCurrentHour()+"\n");
            keyValue.accept( "Background updates (last 24h)", bgStats.scheduledUpdates.getCountForLast24Hours()+"\n");

            final String baseURL = getServletContext().getContextPath();
            sendFileFromClasspath( "/markup/page.html",resp,"text/html",
                Map.of( "baseUrl", baseURL, "tableContent", fragments.toString()));
        }
        resp.getWriter().flush();
    }

    private static void redirectToHomePage(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        final String scheme = req.getScheme();
        final String host = req.getServerName();
        final int serverPort = req.getServerPort();
        final String servletPath = req.getContextPath();
        final boolean isWellKnownPort = serverPort == 80 || serverPort == 443;

        final String home;
        if ( isWellKnownPort ) {
            home = "%s://%s/%s".formatted( scheme, host, servletPath );
        } else {
            home = "%s://%s:%d/%s".formatted( scheme, host, serverPort, servletPath );
        }
        resp.sendRedirect( home );
    }

    private static String resolvePlaceholders(String input, Map<String,String> placeholderValues)
    {
        String source = input;
        final Set<String> keys = new HashSet<>();
        final Matcher m = PLACEHOLDER_PATTERN.matcher( source );
        while ( m.find() ) {
            keys.add( m.group( 1 ) );
        }
        for ( String key : keys ) {
            if ( ! placeholderValues.containsKey( key ) ) {
                throw new RuntimeException( "Unknown placeholder '" + key + "'" );
            }
            final String value = placeholderValues.get( key );
            if ( value == null ) {
                throw new RuntimeException( "NULL value for placeholder '" + key + "' is not supported" );
            }
            source = source.replace( "${" + key + "}", value );
        }
        return source;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        synchronized ( requestsPerHour ) {
            requestsPerHour.update();
        }

        final InputStream in = req.getInputStream();
        final ByteArrayOutputStream reqData = new ByteArrayOutputStream();
        
        Protocol protocol = null;
        try 
        {
            final int protoId = in.read();
            if ( protoId == -1 ) {
                throw new EOFException("Premature end of input, expected protocol ID");
            }
            protocol = Protocol.fromByte( (byte) protoId );
            
            final byte[] binaryResponse = processRequest(in, reqData, protocol);
			resp.getOutputStream().write( binaryResponse );            
            resp.setStatus(200);
        }
        catch(Exception e) 
        {
            final String body;
            if ( protocol == null || reqData.toByteArray().length == 0 ) 
            {
                body = Utils.toHex( reqData.toByteArray() );
            } 
            else 
            {
                body = switch ( protocol ) {
                    case JSON -> reqData.toString( StandardCharsets.UTF_8 );
                    case BINARY -> Utils.toHex( reqData.toByteArray() );
                };
            }
            if ( LOG.isDebugEnabled() ) {
                LOG.error("doPost(): Caught ",e);
                LOG.error("doPost(): BODY = \n=============\n"+body+"\n================");
            } else {
                LOG.error("doPost(): Caught "+e.getMessage()+" from "+req.getRemoteAddr(),e);
            }
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Internal error: "+e.getMessage());            
        }
    }

	public byte[] processRequest(final InputStream in, final ByteArrayOutputStream reqData, Protocol protocol)
			throws Exception {
		final byte[] buffer = new byte[10*1024];
		int len;
		while ( ( len = in.read( buffer ) ) > 0 ) {
		    reqData.write(buffer,0,len);
		}            
		
		return switch( protocol )
		{
		    case BINARY -> processRequest( reqData.toByteArray() );
		    case JSON -> {
                final String body = reqData.toString( StandardCharsets.UTF_8 );
                final String responseJSON = processRequest( body );
                yield responseJSON.getBytes( StandardCharsets.UTF_8 );
            }
		};
	}
    
    public byte[] processRequest(byte[] requestData) throws Exception {
        
        final IBuffer inBuffer = IBuffer.wrap( requestData );
        final BinarySerializer inSerializer = new BinarySerializer(inBuffer);
        final APIRequest apiRequest = APIRequest.deserialize( inSerializer );
        return switch ( apiRequest.command ) {
            case QUERY -> {
                final QueryRequest query = (QueryRequest) apiRequest;
                final QueryResponse response = processQuery( query );
                final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                final IBuffer outBuffer = IBuffer.wrap( byteArrayOutputStream );
                final BinarySerializer outSerializer = new BinarySerializer( outBuffer );
                response.serialize( outSerializer, query.clientVersion.serializationFormat );
                yield byteArrayOutputStream.toByteArray();
            }
        };
    }    
    
    public String processRequest(String jsonRequest) throws Exception {

        final APIRequest apiRequest = parse(jsonRequest, JSON_MAPPER );
        return switch ( apiRequest.command ) {
            case QUERY -> {
                final QueryResponse response = processQuery( (QueryRequest) apiRequest );
                yield JSON_MAPPER.writeValueAsString( response );
            }
        };
    }
    
    public static APIRequest parse(String json,ObjectMapper mapper) throws Exception 
    {
        final APIRequest apiRequest = JSONHelper.parseAPIRequest( json, mapper );
        return switch ( apiRequest.command ) {
            case QUERY -> mapper.readValue( json, QueryRequest.class );
        };
    }

    // TODO: Code almost duplicated in APIImpl#processQuery(QueryRequest) - remove duplication !
    private QueryResponse processQuery(QueryRequest request) throws InterruptedException
    {
        final QueryResponse result = new QueryResponse();

        // Do no cause older clients to crash by reporting a server version they do not know about
        result.serverVersion = switch(request.clientVersion) {
            case V1 -> ServerVersion.V1;
            default -> ServerVersion.latest();
        };

        final APIImpl impl = APIImplHolder.getInstance().getImpl();
        
        final BiPredicate<VersionInfo,Artifact> requiresUpdate;
        if ( artifactUpdatesEnabled ) {
            final IBackgroundUpdater updater = impl.getBackgroundUpdater();
            requiresUpdate = updater::requiresUpdate;
        } else {
            requiresUpdate = (optVersionInfo,art) -> false;
        }
        final Map<Artifact,VersionInfo> results = impl.getVersionTracker().getVersionInfo( request.artifacts, requiresUpdate );        
        for ( Artifact artifact : request.artifacts ) 
        {
            final VersionInfo info = results.get( artifact );

            final ArtifactResponse x = new ArtifactResponse();
            result.artifacts.add(x);
            x.artifact = artifact;
            x.updateAvailable = UpdateAvailable.NOT_FOUND; 

            if ( info != null && info.hasVersions() ) 
            {
                if ( artifact.hasReleaseVersion() ) {
                    final List<Version> versions = info.getVersionsSortedDescending( Artifact::isReleaseVersion, request.blacklist );
                    if ( ! versions.isEmpty() )
                    {
                        if ( versions.size() > 1 ) {
                            x.secondLatestVersion = versions.get(1);
                        }
                        x.latestVersion = versions.get(0);
                        if ( LOG.isDebugEnabled() )
                        {
                            LOG.debug( "processQuery(): latest release version from metadata: " + info.latestReleaseVersion );
                            LOG.debug( "processQuery(): Calculated latest release version: " + x.latestVersion );
                        }
                        if ( !Objects.equals( x.latestVersion, info.latestReleaseVersion ) )
                        {
                            LOG.warn( "processQuery(): Artifact " + info.artifact + " - latest release by date: " + x.latestVersion + ", latest according to meta data: " + info.latestReleaseVersion );
                        }
                    }
                } else {
                    final List<Version> versions = info.getVersionsSortedDescending( Artifact::isSnapshotVersion, request.blacklist );
                    if ( ! versions.isEmpty() )
                    {
                        if ( versions.size() > 1 ) {
                            x.secondLatestVersion = versions.get(1);
                        }
                        x.latestVersion = versions.get(0);
                        if ( LOG.isDebugEnabled() )
                        {
                            LOG.debug( "processQuery(): latest release version from metadata: " + info.latestSnapshotVersion );
                            LOG.debug( "processQuery(): Calculated latest snapshot version: " + x.latestVersion );
                        }
                        if ( !Objects.equals( x.latestVersion, info.latestSnapshotVersion ) )
                        {
                            LOG.warn( "processQuery(): Artifact " + info.artifact + " - latest SNAPSHOT release by date: " + x.latestVersion + ", latest SNAPSHOT release according to meta data: " + info.latestSnapshotVersion );
                        }
                    }
                }

                if ( artifact.version == null || x.latestVersion == null ) 
                {
                    x.updateAvailable = UpdateAvailable.MAYBE;
                } 
                else 
                {
                    final Optional<Version> currentVersion = info.getVersion( artifact.version );
                    currentVersion.ifPresent( version -> x.currentVersion = version );

                    int cmp = Artifact.VERSION_COMPARATOR.compare( artifact.version, x.latestVersion.versionString);
                    if ( cmp >= 0 ) {                    	
                        x.updateAvailable = UpdateAvailable.NO;
                    } else {
                        x.updateAvailable = UpdateAvailable.YES;                    	
                    }                     
                }
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("processQuery(): "+artifact+" <-> "+x.latestVersion+" => "+x.updateAvailable);
                }
            }
        }
        return result;
    }
 
    public void setArtifactUpdatesEnabled(boolean artifactUpdatesEnabled) {
        this.artifactUpdatesEnabled = artifactUpdatesEnabled;
    }

    private Optional<String> getApplicationVersion() {
        return readKeyValue( "/META-INF/maven/de.codesourcery.versiontracker/versiontracker-server/pom.properties",'=',"version");
    }

    private Optional<String> getSHA1Hash() {
        return readKeyValue( "/META-INF/MANIFEST.MF",':',"git-SHA-1");
    }

    private Optional<String> readKeyValue(String classpathLocation,char separator, String key) {
        try {
            return readLines( classpathLocation )
                .map( line -> {
                    final String[] parts = line.split( Pattern.quote(Character.toString(separator)) );
                    return Optional.ofNullable( parts.length > 1 && key.equals( parts[0] ) ? parts[1] : null );
                })
                .flatMap( Optional::stream ).findFirst();
        }
        catch ( Exception e ) {
            LOG.error( "getSHA1Hash(): Failed to get '"+key+"' from "+classpathLocation, LOG.isDebugEnabled() ? e : null );
        }
        return Optional.empty();
    }

    private Stream<String>  readLines(String classpathLocation) throws IOException {
        try ( InputStream inStream = getServletContext().getResourceAsStream(classpathLocation) ) {
            if ( inStream != null ) {
                final String s = new String( inStream.readAllBytes(), StandardCharsets.UTF_8 );
                return Arrays.stream( s.split( "\n" ) );
            }
        }
        return Stream.empty();
    }
}