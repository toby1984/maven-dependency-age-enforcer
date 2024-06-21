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

import de.codesourcery.versiontracker.client.api.IAPIClient.Protocol;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.ArtifactResponse.UpdateAvailable;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.QueryRequest;
import de.codesourcery.versiontracker.common.QueryResponse;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;

public class APIImpl implements AutoCloseable
{
    private static final Logger LOG = LogManager.getLogger(APIImpl.class);

    /**
     * Environment variable that points to the location where
     * artifact metadata should be stored when using the simple flat-file storage implementation.
     */
    public static final String SYSTEM_PROPERTY_ARTIFACT_FILE = "versiontracker.artifact.file";    

    private IVersionTracker versionTracker;
    private IVersionStorage versionStorage;
    private IVersionProvider versionProvider;

    private IBackgroundUpdater updater;
    
    private boolean registerShutdownHook = true;

    private String repo1BaseUrl = MavenCentralVersionProvider.DEFAULT_REPO1_BASE_URL;
    private String restApiBaseUrl = MavenCentralVersionProvider.DEFAULT_SONATYPE_REST_API_BASE_URL;

    public enum Mode
    {
        /**
         * Client mode - does not use a background thread to periodically check registered artifacts for newer releases.
         * <p>
         * If artifact metadata is deemed too old it will be re-fetched again while the client is blocked waiting.
         * </p>
         */
        CLIENT,
        /**
         * Server mode - uses a background thread to periodically check all registered artifacts for newer releases.
         * <p>
         * Artifact metadata should never be outdated as the background thread should've updated it already.
         * </p>
         */
        SERVER
    }

    private final Mode mode;

    private boolean initialized;

    public APIImpl(Mode mode) {
        if ( mode == null ) {
            throw new IllegalArgumentException("Mode cannot be NULL");
        }
        this.mode = mode;
    }
    
    private void setLogLevel(Level level) 
    {
        // taken from https://stackoverflow.com/questions/23434252/programmatically-change-log-level-in-log4j2
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration config = ctx.getConfiguration();
        final LoggerConfig loggerConfig = config.getLoggerConfig("de.codesourcery");
        loggerConfig.setLevel(level);
        ctx.updateLoggers();
    }

    protected IVersionStorage createVersionStorage()
    {
        File versionFile = getArtifactFileLocation();

        Protocol fileType;
        try
        {
            if ( versionFile.exists() && versionFile.length() > 0 )
            {
                fileType = FlatFileStorage.guessFileType( versionFile ).orElse( null );
                if ( fileType == null ) {
                    LOG.error( "createVersionStorage(): Unable to determine file type of '" + versionFile + "'" );
                    throw new RuntimeException( "Unable to determine file type of data storage file '" + versionFile + "'" );
                }
            } else {
                fileType = versionFile.getName().endsWith( ".json" ) ? Protocol.JSON : Protocol.BINARY;
            }
        }
        catch( IOException e )
        {
            LOG.error( "createVersionStorage(): Failed to read '" + versionFile + "'", e );
            throw new UncheckedIOException(e);
        }

        // migrate JSON file to binary
        if ( fileType == Protocol.JSON && versionFile.exists() && versionFile.length() > 0 )
        {
            final File binaryFile = new File( versionFile.getAbsolutePath()+".binary");
            try
            {
                if ( ! binaryFile.exists() )
                {
                    LOG.warn( "createVersionStorage(): Using JSON files for storage is deprecated, trying to convert " +
                        versionFile.getAbsolutePath() + " -> " + binaryFile.getAbsolutePath() );
                    FlatFileStorage.convert( versionFile, Protocol.JSON, binaryFile, Protocol.BINARY );
                    LOG.info( "createVersionStorage(): Converted " + versionFile.getAbsolutePath() + " -> " + binaryFile.getAbsolutePath() );
                } else {
                    LOG.warn("createVersionStorage(): Configuration tells to use deprecated JSON file "+versionFile+" " +
                        "but binary file "+binaryFile+" exists, will use the latter. Please update your configuration to use the binary file instead.");
                }
                versionFile = binaryFile;
                fileType = Protocol.BINARY;
            }
            catch( Exception e )
            {
                LOG.error( "createVersionStorage(): Using JSON file , failed to convert " + versionFile.getAbsolutePath() + " -> "
                    + binaryFile.getAbsolutePath(), e );
            }
        }

        LOG.info("init(): Using "+fileType+" file "+versionFile.getAbsolutePath());
        final IVersionStorage fileStorage = new FlatFileStorage( versionFile, fileType );
        return new CachingStorageDecorator( fileStorage );
    }

    // unit-testing hook
    protected IVersionProvider createVersionProvider() {
        return new MavenCentralVersionProvider( repo1BaseUrl, restApiBaseUrl );
    }

    // unit-testing hook
    protected IBackgroundUpdater createBackgroundUpdater(SharedLockCache lockCache) {
        return new BackgroundUpdater(versionStorage,versionProvider,lockCache);
    }

    // unit-testing hook
    protected IVersionTracker createVersionTracker(SharedLockCache lockCache) {
        return new VersionTracker( versionStorage, versionProvider, lockCache );
    }

    public synchronized void init(boolean debugMode,boolean verboseMode)
    {
        if ( initialized ) {
            return;
        }

        initialized = true;

        versionStorage  = createVersionStorage();
        versionProvider = createVersionProvider();
        final SharedLockCache lockCache = new SharedLockCache();
        
        if ( debugMode || verboseMode ) 
        {
            if ( debugMode ) {
                setLogLevel( Level.DEBUG );
            } else {
                setLogLevel( Level.INFO);
            }
        }

        LOG.info("init(): ====================");
        LOG.info("init(): Running in "+mode+" mode.");

        if ( registerShutdownHook ) 
        {
            LOG.info("init(): Registering shutdown hook");
            Runtime.getRuntime().addShutdownHook( new Thread( () -> 
            {
                try {
                    LOG.info("init(): Shutdown hook triggered");
                    close();
                } catch (Exception e) {
                    LOG.error("Exception during shutdown: "+e.getMessage(),e);
                }
            }));
        }

        updater = createBackgroundUpdater(lockCache);
        if ( mode == Mode.SERVER ) 
        {
            LOG.info("init(): Starting background update thread.");        
            updater.startThread();
        } 

        boolean success = false;
        try 
        {
            final int threadCount = Runtime.getRuntime().availableProcessors()*2;
            versionTracker = createVersionTracker( lockCache );
            versionTracker.setMaxConcurrentThreads( threadCount );

            LOG.info("init(): Initialization done.");
            LOG.info("init(): ");
            LOG.info("init(): Version file storage: "+versionStorage);
            LOG.info("init(): Maven repository enpoint: "+ repo1BaseUrl );
            LOG.info("init(): Thread count: "+versionTracker.getMaxConcurrentThreads());
            LOG.info("init(): ====================");
            success = true;
        } 
        finally 
        {
            if ( ! success ) 
            {
                LOG.error("init(): Initialization failed");
                try 
                {
                    updater.close();
                }
                catch (Exception e) 
                {
                    LOG.error("init(): Caught "+e.getMessage(),e);
                }
            }
        }
    }

    private File getArtifactFileLocation()
    {
        String location = System.getProperty( SYSTEM_PROPERTY_ARTIFACT_FILE );
        if ( StringUtils.isNotBlank( location ) ) 
        {
            LOG.info("getArtifactFileLocation(): Using artifacts file location from '"+SYSTEM_PROPERTY_ARTIFACT_FILE+"' JVM property");
            return new File( location );
        }
        location = System.getProperty("user.home");
        if ( StringUtils.isNotBlank( location) ) 
        {
            LOG.info("getArtifactFileLocation(): Storing artifacts file relative to 'user.home' JVM property");
            final File fallback = new File( location , "artifacts.json");
            final File m2Dir = new File( location , ".m2");
            if ( m2Dir.exists() ) 
            {
                if ( ! m2Dir.isDirectory() ) {
                    return fallback;
                }
                return new File( m2Dir , "artifacts.json" );
            } 
            if ( m2Dir.mkdirs() ) {
                LOG.info("getArtifactFileLocation(): Created directory "+m2Dir.getAbsolutePath());
                return new File( m2Dir , "artifacts.json" );
            }
            return fallback;
        }
        final String msg = "Neither 'user.home' nor '"+SYSTEM_PROPERTY_ARTIFACT_FILE+"' JVM properties are set, don't know where to store artifact metadata";
        LOG.error("getArtifactFileLocation(): "+msg);
        throw new RuntimeException(msg);
    }      

    // TODO: Code almost duplicated in APIServlet#processQuery(QueryRequest) - remove duplication !
    public QueryResponse processQuery(QueryRequest request) throws InterruptedException
    {
        QueryResponse result = new QueryResponse();

        final Map<Artifact,VersionInfo> results = versionTracker.getVersionInfo( request.artifacts, updater::requiresUpdate );        
        for ( Artifact artifact : request.artifacts ) 
        {
            final VersionInfo info = results.get( artifact );
            if ( info == null ) {
                throw new RuntimeException("Got no result for "+artifact+"?");
            }

            final ArtifactResponse x = new ArtifactResponse();
            result.artifacts.add(x);
            x.artifact = artifact;
            x.updateAvailable = UpdateAvailable.NOT_FOUND; 

            if ( info.hasVersions() )
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

    public Mode getMode()
    {
        return mode;
    }

    public IBackgroundUpdater getBackgroundUpdater()
    {
        return updater;
    }

    public IVersionTracker getVersionTracker()
    {
        return versionTracker;
    }

    @Override
    public void close() throws Exception
    {
        try 
        {
            if ( updater != null ) 
            {
                updater.close();
            }
        } 
        finally 
        {
            try 
            {
                if ( versionTracker != null ) 
                {
                    versionTracker.close();
                }
            } 
            finally 
            {
                if ( this.versionStorage != null ) {
                    this.versionStorage.close();
                }
            }
        } 
    }

    public void setRegisterShutdownHook(boolean registerShutdownHook)
    {
        this.registerShutdownHook = registerShutdownHook;
    }

}