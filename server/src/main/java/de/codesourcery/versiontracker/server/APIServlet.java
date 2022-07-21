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
import de.codesourcery.versiontracker.client.IAPIClient.Protocol;
import de.codesourcery.versiontracker.common.APIRequest;
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
import de.codesourcery.versiontracker.common.Utils;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;
import de.codesourcery.versiontracker.common.server.APIImpl;
import de.codesourcery.versiontracker.common.server.IBackgroundUpdater;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Servlet responsible for processing {@link APIRequest}s.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class APIServlet extends HttpServlet
{
    private static final Logger LOG = LogManager.getLogger(APIServlet.class);

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
        return dt.format( formatter );
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {

        final APIImpl impl = APIImplHolder.getInstance().getImpl();
        final IVersionStorage storage = impl.getVersionTracker().getStorage();

        if ( req.getRequestURI().endsWith("/simplequery") )
        {
            final Artifact a = new Artifact();
            a.artifactId = req.getParameter( "artifactId" );
            a.groupId = req.getParameter( "groupId" );
            a.setClassifier( req.getParameter( "classifier" ) );
            a.type = req.getParameter( "type" );
            if ( a.type == null) {
                a.type = "jar";
            }
            final List<VersionInfo> result = new ArrayList<>();
            if ( req.getParameter( "regex" ) != null ) {
                result.addAll( storage.getAllVersions( a.groupId, a.artifactId ) );
                result.removeIf( toCheck -> {
                   if ( ! Objects.equals( toCheck.artifact.type, a.type ) ) {
                       return true;
                   }
                    return a.getClassifier() != null && !Objects.equals( toCheck.artifact.getClassifier(), a.getClassifier() );
                });
            } else {
                storage.getVersionInfo( a ).ifPresent( result::add );
            }
            resp.setContentType( "application/json" );
            resp.getWriter().write( JSON_MAPPER.writeValueAsString( result ) );
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

            float sizeInMB = storageStats.storageStatistics.storageSizeInBytes/(1024*1024.0f);
            keyValue.accept( "On-disk storage (MB)", new DecimalFormat("######0.0#").format( sizeInMB ) );

            keyValue.accept( "HTTP POST requests (current hour)", storageStats.httpStats.getCountForCurrentHour() );
            keyValue.accept( "HTTP POST requests (last 24 hours)", storageStats.httpStats.getCountForLast24Hours() );

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

            keyValue.accept( "Repo release date fetch (most recent)", repoStats.releaseDateRequests.getMostRecentAccess().map( APIServlet::toString).orElse("n/a"));
            keyValue.accept( "Repo release date fetches (current hour)", repoStats.releaseDateRequests.getCountForCurrentHour()+"\n");
            keyValue.accept( "Repo release date fetches (last 24h)", repoStats.releaseDateRequests.getCountForLast24Hours()+"\n");

            final String html = """
                <html>
                <head>
                  <style>
                    div.row {
                      border: 1px solid black;
                    }
                    div.cellName {
                      display:inline-block;
                      width:400px;
                    }
                    div.cellValue {
                      display:inline-block;
                    }
                    div.table {
                      border: 1px solid black;
                    }
                  </style>
                </head>
                <body>
                  <div class="table">
                  %s
                  </div>
                </body>
                </html>
                """.formatted(fragments);

            resp.getWriter().write( html );
        }
        resp.getWriter().flush();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
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
            requestsPerHour.update();
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
                response.serialize( outSerializer );
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
        QueryResponse result = new QueryResponse();
        result.serverVersion = "1.0";

        final APIImpl impl = APIImplHolder.getInstance().getImpl();
        
        final Predicate<Optional<VersionInfo>> requiresUpdate;
        if ( artifactUpdatesEnabled ) {
            final IBackgroundUpdater updater = impl.getBackgroundUpdater();
            requiresUpdate = updater::requiresUpdate;
        } else {
            requiresUpdate = optVersionInfo -> false;
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
                    x.latestVersion = info.findLatestReleaseVersion( request.blacklist ).orElse( null );
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("processQuery(): latest release version from metadata: "+info.latestReleaseVersion);
                        LOG.debug("processQuery(): Calculated latest release version: "+x.latestVersion);
                    }
                } else {
                    x.latestVersion = info.findLatestSnapshotVersion( request.blacklist ).orElse( null );
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("processQuery(): latest release version from metadata: "+info.latestSnapshotVersion);
                        LOG.debug("processQuery(): Calculated latest snapshot version: "+x.latestVersion);
                    }
                }

                if ( artifact.version == null || x.latestVersion == null ) 
                {
                    x.updateAvailable = UpdateAvailable.MAYBE;
                } 
                else 
                {
                    final Optional<Version> currentVersion = info.getVersion( artifact.version );
                    if ( currentVersion.isPresent() ) {
                        x.currentVersion = currentVersion.get();
                    }

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