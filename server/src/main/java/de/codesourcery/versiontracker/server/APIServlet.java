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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.codesourcery.versiontracker.client.IAPIClient.Protocol;
import de.codesourcery.versiontracker.common.APIRequest;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.ArtifactResponse.UpdateAvailable;
import de.codesourcery.versiontracker.common.BinarySerializer;
import de.codesourcery.versiontracker.common.BinarySerializer.IBuffer;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.JSONHelper;
import de.codesourcery.versiontracker.common.QueryRequest;
import de.codesourcery.versiontracker.common.QueryResponse;
import de.codesourcery.versiontracker.common.Utils;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;
import de.codesourcery.versiontracker.common.server.APIImpl;
import de.codesourcery.versiontracker.common.server.BackgroundUpdater;

/**
 * Servlet responsible for processing {@link APIRequest}s.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class APIServlet extends HttpServlet
{
    private static final Logger LOG = LogManager.getLogger(APIServlet.class);

    public interface IUpdateCallback 
    {
        public void received(IVersionProvider.UpdateResult updateResult,VersionInfo info,Exception exception);
    }

    private ThreadLocal<ObjectMapper> mapper = new ThreadLocal<>() 
    {
     protected ObjectMapper initialValue() {
         return JSONHelper.newObjectMapper();
     }
    };
    
    private boolean artifactUpdatesEnabled = false; // FIXME: Debug, change to TRUE!!
    
    public APIServlet() {
        LOG.info("APIServlet(): Instance created");
    }

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        if ( LOG.isInfoEnabled() ) {
            LOG.debug("service(): Incoming request from "+((HttpServletRequest) req).getRemoteAddr());
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

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
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
                switch( protocol ) 
                {
                    case JSON:
                        body = new String( reqData.toByteArray(), "UTF8" );
                        break;
                    default:
                        body = Utils.toHex( reqData.toByteArray() );
                }
            }
            if ( LOG.isDebugEnabled() ) {
                LOG.error("doPost(): Caught ",e);
                LOG.error("doPost(): BODY = \n=============\n"+body+"\n================");
            } else {
                LOG.error("doPost(): Caught "+e.getMessage()+" from "+req.getRemoteAddr(),e);
            }
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Internal error: "+e.getMessage());            
            return;
        }
    }

	public byte[] processRequest(final InputStream in, final ByteArrayOutputStream reqData, Protocol protocol)
			throws IOException, Exception, UnsupportedEncodingException 
	{
		final byte[] buffer = new byte[10*1024];
		int len=0;
		while ( ( len = in.read( buffer ) ) > 0 ) {
		    reqData.write(buffer,0,len);
		}            
		
		switch( protocol ) 
		{
		    case BINARY:
		    	return processRequest( reqData.toByteArray() );
		    case JSON:
		        final String body = new String( reqData.toByteArray() , "UTF8" );
		        final String responseJSON = processRequest(body);
		        return responseJSON.getBytes("UTF8");
		    default:
		}
		throw new RuntimeException("Internal error,unhandled protocol "+protocol);
	}
    
    public byte[] processRequest(byte[] requestData) throws Exception {
        
        final IBuffer inBuffer = IBuffer.wrap( requestData );
        final BinarySerializer inSerializer = new BinarySerializer(inBuffer);
        final APIRequest apiRequest = APIRequest.deserialize( inSerializer );
        switch(apiRequest.command) 
        {
            case QUERY:
                final QueryRequest query = (QueryRequest) apiRequest;
                final QueryResponse response = processQuery( query );
                
                final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                final IBuffer outBuffer = IBuffer.wrap( byteArrayOutputStream );
                final BinarySerializer outSerializer = new BinarySerializer(outBuffer);
                response.serialize( outSerializer );
                return byteArrayOutputStream.toByteArray();
            default:
                throw new RuntimeException("Internal error,unhandled command "+apiRequest.command);
        }        
    }    
    
    public String processRequest(String jsonRequest) throws Exception {
        
        final ObjectMapper jsonMapper = mapper.get();
        final APIRequest apiRequest = parse(jsonRequest,jsonMapper);
        switch(apiRequest.command) 
        {
            case QUERY:
                final QueryResponse response = processQuery( (QueryRequest) apiRequest );
                return jsonMapper.writeValueAsString(response);
            default:
                throw new RuntimeException("Internal error,unhandled command "+apiRequest.command);
        }        
    }
    
    public static APIRequest parse(String json,ObjectMapper mapper) throws Exception 
    {
        final APIRequest apiRequest = JSONHelper.parseAPIRequest( json, mapper );
        switch(apiRequest.command) 
        {
            case QUERY:
                return mapper.readValue(json,QueryRequest.class);     
            default:
                throw new RuntimeException("Internal error,unhandled command "+apiRequest.command);
        } 
    }

    private QueryResponse processQuery(QueryRequest request) throws InterruptedException
    {
        QueryResponse result = new QueryResponse();
        result.serverVersion = "1.0";

        final APIImpl impl = APIImplHolder.getInstance().getImpl();
        
        final Predicate<Optional<VersionInfo>> requiresUpdate;
        if ( artifactUpdatesEnabled ) {
            final BackgroundUpdater updater = impl.getBackgroundUpdater();
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
                    final Optional<Version> currentVersion = info.getDetails( artifact.version );
                    if ( currentVersion.isPresent() ) {
                        x.currentVersion = currentVersion.get();
                    }

                    int cmp = Artifact.VERSION_COMPARATOR.compare( artifact.version, x.latestVersion.versionString);
                    if ( cmp >= 0 ) {                    	
                        x.updateAvailable = UpdateAvailable.NO;
                    } else if ( cmp < 0 ) {
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
}