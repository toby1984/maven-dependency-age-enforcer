/**
 * Copyright 2015 Tobias Gierke <tobias.gierke@code-sourcery.de>
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
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.codesourcery.versiontracker.common.APIRequest;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.ArtifactResponse.UpdateAvailable;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
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
    
    private VersionTracker versionTracker;
    
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
            LOG.debug("service(): Called with method "+((HttpServletRequest) req).getMethod());
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
    
    public static void main(String[] args)
    {
        System.getProperties().stringPropertyNames().forEach( key -> System.out.println( key+"="+System.getProperty(key) ) );
    }
    
    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        
        String fileLocation = System.getProperty("user.home");
        if ( StringUtils.isBlank( fileLocation ) ) {
            fileLocation = "/tmp/artifact-metadata.json";
            LOG.warn("service(): 'user.home' is not set, will store artifacts at "+fileLocation);
        } 
        
        final File versionFile = new File( fileLocation );
        final String mavenRepository = "http://repo1.maven.org/maven2/";
        
        final IVersionStorage versionStorage = new FlatFileStorage( versionFile );
        final IVersionProvider versionProvider = new MavenCentralVersionProvider(mavenRepository);          
        versionTracker = new VersionTracker(versionStorage,versionProvider);
        
        final int threadCount = Runtime.getRuntime().availableProcessors()*2;
        versionTracker.setMaxConcurrentThreads( threadCount );
        
        versionTracker.start();        
        
        LOG.info("service(): ====================");
        LOG.info("service(): = Servlet initialized.");
        LOG.info("service():");
        LOG.info("service(): Version file storage: "+versionFile.getAbsolutePath());
        LOG.info("service(): Maven repository enpoint: "+mavenRepository);
        LOG.info("service(): Thread count: "+versionTracker.getMaxConcurrentThreads());
        LOG.info("service(): ====================");
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
    
    private QueryResponse processQuery(QueryRequest request)
    {
        QueryResponse result = new QueryResponse();
        result.serverVersion = "1.0";
        
        final Map<Artifact,VersionInfo> results = versionTracker.getVersionInfo( request.artifacts );        
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
                    LOG.info("processQuery(): latest release version from metadata: "+info.latestReleaseVersion);
                    LOG.info("processQuery(): Calculated latest release version: "+x.latestVersion);
                } else {
                    x.latestVersion = info.findLatestSnapshotVersion( request.blacklist ).orElse( null );
                    LOG.info("processQuery(): latest release version from metadata: "+info.latestSnapshotVersion);
                    LOG.info("processQuery(): Calculated latest snapshot version: "+x.latestVersion);
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
                    if ( cmp > 1 || cmp == 0 ) {                    	
                    	x.updateAvailable = UpdateAvailable.NO;
                    } else if ( cmp < 1 ) {
                    	x.updateAvailable = UpdateAvailable.YES;                    	
                    }                     
                }
                LOG.info("processQuery(): "+artifact+" <-> "+x.latestVersion+" => "+x.updateAvailable);
            }
		}
        return result;
    }    
}