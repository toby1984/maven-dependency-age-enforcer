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

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.codesourcery.versiontracker.common.APIRequest;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.ArtifactResponse.UpdateAvailable;
import de.codesourcery.versiontracker.common.server.APIImpl;
import de.codesourcery.versiontracker.common.server.APIImpl.Mode;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.JSONHelper;
import de.codesourcery.versiontracker.common.QueryRequest;
import de.codesourcery.versiontracker.common.QueryResponse;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;

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

    public APIServlet() {
        LOG.info("APIServlet(): Instance created");
    }

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        if ( LOG.isDebugEnabled() ) {
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
            if ( LOG.isDebugEnabled() ) {
                final long elapsed = System.currentTimeMillis() - start;
                LOG.debug("service(): Request finished after "+elapsed+" ms");
            }
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        final BufferedReader reader = req.getReader();
        final String body = IOUtils.readLines( reader ).stream().collect( Collectors.joining() );

        try 
        {
            LOG.info("doPost(): BODY = \n=============\n"+body+"\n================");
            APIRequest apiRequest = JSONHelper.parseAPIRequest( body );

            switch(apiRequest.command) 
            {
                case QUERY:
                    final ObjectMapper mapper = JSONHelper.newObjectMapper();
                    final QueryRequest query = mapper.readValue(body,QueryRequest.class);     
                    final QueryResponse response = processQuery( query );

                    final String responseJSON = mapper.writeValueAsString(response);
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("doPost(): RESPONSE: \n"+responseJSON+"\n");
                    }
                    resp.getWriter().write(  responseJSON );                                
                    resp.setStatus(200);
                    break;
                default:
                    resp.sendError(502,"Unknown command");
                    return;
            }
        } 
        catch(Exception e) 
        {
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

    private QueryResponse processQuery(QueryRequest request) throws InterruptedException
    {
        QueryResponse result = new QueryResponse();
        result.serverVersion = "1.0";

        final APIImpl impl = APIImplHolder.getInstance().getImpl();
        final Map<Artifact,VersionInfo> results = impl.getVersionTracker().getVersionInfo( request.artifacts, impl.getBackgroundUpdater() );        
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
}