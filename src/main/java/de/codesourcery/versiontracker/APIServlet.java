package de.codesourcery.versiontracker;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class APIServlet extends HttpServlet
{
    private static final Logger LOG = LogManager.getLogger(APIServlet.class);
    
    public static enum Command 
    {
        IS_LATEST_VERSION("islatestversion");
        
        public String cmd;

        private Command(String cmd)
        {
            this.cmd = cmd;
        }
    }
    
    private Duration pollingInterval = Duration.ofHours( 24 );
    
    private IVersionStorage versionStorage;
    private IVersionProvider versionProvider;
    
    private final Object VERSIONS_LOCK = new Object();
    private final List<VersionInfo> trackedVersions = new ArrayList<>(); 

    private PollingThread pollingThread;
    
    private Optional<VersionInfo> getVersionInfo(Artifact artifact) 
    {
        synchronized( VERSIONS_LOCK ) 
        {
            final Optional<VersionInfo> match = trackedVersions.stream().filter( x -> x.artifact.matchesExcludingVersion( artifact ) ).findFirst();
            if ( match.isPresent() ) {
                return Optional.of( match.get().copy() );
            }
            final VersionInfo newItem = new VersionInfo();
            newItem.artifact = artifact.copy();
            newItem.creationDate = ZonedDateTime.now();
            trackedVersions.add( newItem );
        }
        return Optional.empty();
    }
    
    protected final class PollingThread extends Thread
    {
        {
            setDaemon(true);
            setName("watchdog");
        }
        
        @Override
        public void run()
        {
            trackedVersions.clear();
            try {
                trackedVersions.addAll( versionStorage.getAllVersions() );
            } 
            catch (IOException e) {
                LOG.error("run(): Loading versions failed");
                e.printStackTrace();
            }
            LOG.info("run(): Background thread started, tracking "+trackedVersions.size()+" versions");
            
            while ( true ) 
            {
                final ZonedDateTime now = ZonedDateTime.now(); 
                final List<VersionInfo> outdated = new ArrayList<>();
                List<VersionInfo> copy;
                synchronized(VERSIONS_LOCK) 
                {
                    copy = new ArrayList<>( trackedVersions.stream().map( x -> x.copy() ).collect( Collectors.toList() ) );
                }
                for ( VersionInfo info : copy) 
                {
                    if ( info.lastPolledDate() == null || Duration.between( info.lastPolledDate(), now ).compareTo( pollingInterval ) > 0 ) 
                    {
                        outdated.add( info );
                    }
                }
                
                if ( outdated.size() > 0) 
                {
                    LOG.debug("run(): Fetching info for "+outdated.size()+" outdated versions");
                    final CountDownLatch latch = new CountDownLatch(outdated.size());
                    final List<VersionInfo> newItems = new ArrayList<>();
                    
                    for (VersionInfo item : outdated ) 
                    {
                        update(item,(info,error)-> 
                        {
                            if ( error == null ) 
                            {
                                if ( LOG.isDebugEnabled() ) {
                                    LOG.debug("run(): Failed to fetch version information for "+item,error);
                                } else {
                                    LOG.debug("run(): Failed to fetch version information for "+item+": "+error.getMessage());
                                }
                                item.lastFailureDate = now;
                                newItems.add( item ); // keep the old item for now...                            
                            } 
                            else 
                            {
                                if ( info.isPresent() ) 
                                {
                                    final VersionInfo newItem = info.get();
                                    LOG.debug("run(): Got info: "+newItem);
                                    newItem.lastSuccessDate = now;
                                    synchronized(newItems) 
                                    {
                                        newItems.add( newItem );
                                    }
                                } else {
                                    LOG.debug("run(): Failed to fetch version information for "+item+" (not found)");
                                    item.lastFailureDate = now;
                                    newItems.add( item ); // keep the old item for now...
                                }
                            }
                        });
                    }
                    
                    final long start = System.currentTimeMillis();
                    while ( true ) 
                    {
                        try {
                            if ( latch.await( 2, TimeUnit.MINUTES ) ) {
                                break;
                            }
                        } catch (InterruptedException e) {
                        }
                        final long elapsedMinutes = ((System.currentTimeMillis() - start)/1000)/60;
                        LOG.warn("run(): Already waiting for "+elapsedMinutes+" minutes");
                    }
                    synchronized(VERSIONS_LOCK) 
                    {
                        trackedVersions.removeAll( outdated );
                        trackedVersions.addAll( newItems );
                        
                        copy = new ArrayList<>( trackedVersions.stream().map( x -> x.copy() ).collect( Collectors.toList() ) );
                    }
                    try 
                    {
                        versionStorage.saveAll( copy );
                    } 
                    catch (Exception e) 
                    {
                        LOG.error("run(): Failed to save tracked versions",e);
                    }                        
                }
                
                try {
                    java.lang.Thread.sleep( 10 );
                } catch(Exception e) {
                }
            }
        }
        
        private void update(VersionInfo info,BiConsumer<Optional<VersionInfo>,Exception> callback) 
        {
            Optional<VersionInfo> result = Optional.empty(); 
            Exception ex = null;
            try {
                result = versionProvider.getLatestVersion( info.artifact );
            } 
            catch (Exception e) {
                ex = e;
            }
            callback.accept(result,ex);
        }
    }
    
    public APIServlet() {
        LOG.info("APIServlet(): Loaded");
    }
    
    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        LOG.info("service(): Called");
        super.service(req, res);
    }
    
    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        pollingThread = new PollingThread();
        pollingThread.start();
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        final String uri = req.getRequestURI();
        final String ctxPath = req.getContextPath();
        final String stripped = uri.substring( ctxPath.length() );
        LOG.info("service(): stripped = "+stripped);
        final String[] components = stripped.split("/");
        if ( components.length < 2) 
        {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Not a valid API request");
            return;
        }
        try 
        {
            for ( Command cmd : Command.values() ) 
            {
                if ( cmd.cmd.equals( components[1] ) ) {
                    handleRequest(cmd, Arrays.copyOfRange(components,2,components.length), resp );
                    return;
                }
            }
            throw new RuntimeException("Unrecognized command '"+stripped+"'");
        } 
        catch(Exception e) 
        {
            LOG.error("doGet(): Something went from while processing "+stripped,e);
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Internal error: "+e.getMessage());
        }
    }
    
    private void handleRequest(Command command,String[] arguments,HttpServletResponse response) throws IOException
    {
        LOG.debug("handleRequest(): "+command+" => "+Stream.of(arguments).collect(Collectors.joining(",")) );
        
        switch( command ) 
        {
            case IS_LATEST_VERSION:
                /*
        if ( artifact.classifier != null ) {
            return artifact.groupId.replace('.','/')+"/"+artifact.artifactId+"/"
                    +artifact.version+"/"+artifact.artifactId+"-"+artifact.version+"-"+artifact.classifier+"."+artifact.type;
                    /groupId/artifactId/version/type/classifier
                 */
                if ( arguments.length < 4 || arguments.length > 5 ) 
                {
                    final Artifact artifact = new Artifact();
                    artifact.groupId = arguments[0];
                    artifact.artifactId = arguments[1];
                    artifact.version = arguments[2];
                    artifact.type = arguments[3];
                    if ( arguments.length == 5 ) {
                        artifact.classifier = arguments[4];
                    }
                    
                    final Optional<VersionInfo> result = getVersionInfo( artifact );
                    if ( result.isPresent() ) 
                    {
                        final int cmp = getVersionComparator( artifact ).compare( artifact, result.get() );
                        if ( cmp == 0 ) {
                            LOG.debug("handleRequest(): Artifact already has the latest version");
                            response.getOutputStream().write( new String("Artifact already has the latest version").getBytes() );
                        } else if ( cmp < 0 ) {
                            LOG.debug("handleRequest(): Artifact is outdated");
                            response.getOutputStream().write( new String("Artifact is outdated").getBytes() );
                        } else {
                            LOG.debug("handleRequest(): Artifact is newer than latest version?");
                            response.getOutputStream().write( new String("Artifact is newer than latest version?").getBytes() );
                        }
                    } else {
                        response.getOutputStream().write( new String("Artifact not queried yet, please try again later").getBytes() );
                    }
                } else {
                    throw new RuntimeException("Invalid URL for "+command);
                }
                break;
            default:
                throw new RuntimeException("Command not implemented: "+command);
        }
    }

    private IVersionComparator getVersionComparator(Artifact artifact) {
        return new IVersionComparator() 
        {
            public int compare(Artifact versionA,VersionInfo versionB) 
            {
               final String latest;
               if ( versionA.hasSnapshotVersion() ) {
                   latest = normalize(versionB.latestSnapshotVersion);
               } else {
                   latest = normalize(versionB.latestReleaseVersion);
               }
               final String actual = normalize( versionA.version );
               final String[] parts1 = actual.split("\\.");
               final String[] parts2 = latest.split("\\.");
               final int maxLen = parts1.length > parts2.length ? parts1.length : parts2.length;
               for ( int i = 0 ; i < maxLen ; i++ ) 
               {
                   try {
                       int a = Integer.parseInt( parts1[i] );
                       int b = Integer.parseInt( parts1[2] );
                       if ( a < b ) {
                           return -1;
                       }
                       if ( a > b ) {
                           return 1;
                       }
                   } 
                   catch(Exception e) 
                   {
                       LOG.error("compare(): While comparing actual '"+actual+"' <> latest '"+latest+"'",e);
                       e.printStackTrace();
                   }
               }
               return 0;
            }
            
            private String normalize(String input) {
                return input;
            }
        };
    }
}
