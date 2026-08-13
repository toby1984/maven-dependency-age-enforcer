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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import de.codesourcery.versiontracker.common.Blacklist;

public class Configuration
{
    private static final Logger LOG = LogManager.getLogger( Configuration.class );
    private static final String DEFAULT_CONFIG_FILE_LOCATION = "classpath:/versionTracker.json";
    public static final String CONFIG_FILE_LOCATION_SYS_PROPERTY = "versionTracker.configFile";

    public record DurationRange(Duration min, Duration max)
    {
        public DurationRange
        {
            Validate.notNull( min, "min must not be null" );
            Validate.notNull( max, "max must not be null" );
            Validate.isTrue( min.compareTo( max ) <= 0 , "min > max ?" );
        }
    }

    private volatile Blacklist blacklist = new Blacklist();
    /**
     * Time to wait before retrying artifact metadata retrieval if the last
     * attempt FAILED.
     */
    private volatile Duration minUpdateDelayAfterFailure = Duration.ofDays( 1 );

    /**
     * Time to wait before retrying artifact metadata retrieval if the last
     * attempt was a SUCCESS.
     */
    private volatile Duration minUpdateDelayAfterSuccess = Duration.ofDays( 1 );

    /**
     * Time the background thread will sleep() before checking the backing storage
     * for stale artifact metadata.
     */
    private volatile Duration bgUpdateCheckInterval = Duration.ofMinutes( 1 );

    private volatile File dataStore;

    /**
     * Controls behaviour when a client
     * requests information about a stale artifact.
     * <p>
     * Requesting information about a new/missing artifact
     * will always yield a synchronous call to the
     * current {@link de.codesourcery.versiontracker.common.IVersionProvider}
     * as obviously the client cannot check the age without ANY information.
     */
    public enum ClientRequestArtifactUpdateMode
    {
        /**
         * Update stale artifacts while handling the client request.
         * This may lead to TCP read() timeouts on the client if the
         * {@link de.codesourcery.versiontracker.common.IVersionProvider} is slow
         * to respond or the client request refers to a lot of stale/unknown artifacts.
         */
        SYNC( "sync" ),
        /**
         * If the previous artifact update finished successfully but the artifact is
         * currently considered to be stale, let the client use that stale information and hope the
         * next background update succeeds as well.
         */
        ASYNC_ONLY_WHEN_SUCCESSFUL( "async-only-when-successful" ),
        /**
         * Always only rely on the background update to fix things when a stale artifact is
         * encountered, never block the client if we have ANY information
         * about that artifact already. Instead, that stale information gets returned.
         */
        ASYNC( "async" ),
        ;
        private final String configLiteral;

        ClientRequestArtifactUpdateMode(String configLiteral)
        {
            this.configLiteral = configLiteral;
        }

        public static Optional<ClientRequestArtifactUpdateMode> fromString(String s)
        {
            for ( final ClientRequestArtifactUpdateMode value : values() )
            {
                if ( s.equalsIgnoreCase( value.configLiteral ) )
                {
                    return Optional.of( value );
                }
            }
            return Optional.empty();
        }
    }
    /**
     * Whether artifacts whose last fetch was successful but
     * are stale should be updated synchronously when
     * a client requests them or should only ever be updated in the background.
     *
     * Synchronous updates carry a high risk of making the client run
     * into a read() timeout so it's best to only perform the absolutely
     * necessary updates synchronously.
     */
    private volatile ClientRequestArtifactUpdateMode clientArtifactUpdateMode =
        ClientRequestArtifactUpdateMode.ASYNC_ONLY_WHEN_SUCCESSFUL;

    private volatile DurationRange backgroundUpdateDelayWindowInMinutes =
        new DurationRange( Duration.ofMinutes( 10 ), Duration.ofHours( 2 ) );

    public final ZonedDateTime timestamp = ZonedDateTime.now();

    public interface IResource {
        boolean exists();
        InputStream open() throws IOException;
        Optional<ZonedDateTime> lastChangeDate();
    }

    private static final class ClasspathResource implements IResource {

        public final String classpath;

        private ClasspathResource(String classpath)
        {
            Validate.notBlank( classpath, "classpath must not be null or blank");
            this.classpath = classpath;
        }

        @Override
        public boolean exists()
        {
            try ( InputStream in = Configuration.class.getResourceAsStream( classpath ) ) {
                return in != null;
            } catch(Exception e) {
                return false;
            }
        }

        @Override
        public InputStream open() throws IOException
        {
            InputStream in = Configuration.class.getResourceAsStream( classpath );
            if ( in == null ) {
                throw new FileNotFoundException( "Failed to open classpath resource " + this );
            }
            return in;
        }

        @Override
        public Optional<ZonedDateTime> lastChangeDate()
        {
            // TODO: Implement me
            throw new UnsupportedOperationException( "Method lastChangeDate not implemented" );
        }

        @Override
        public String toString()
        {
            return "classpath:"+classpath;
        }
    }
    private static final class FileResource implements IResource {

        public final File file;

        private FileResource(File file)
        {
            Validate.notNull( file, "file must not be null" );
            this.file = file;
        }

        @Override
        public boolean exists()
        {
            return file.exists();
        }

        @Override
        public InputStream open() throws IOException
        {
            return new FileInputStream( file );
        }

        @Override
        public Optional<ZonedDateTime> lastChangeDate()
        {
            try
            {
                final FileTime time = Files.getLastModifiedTime( file.toPath() );
                return Optional.of( time.toInstant().atZone( ZoneId.systemDefault() ) );
            }
            catch( IOException e )
            {
                LOG.warn( "Failed to get file modification time from '" + file + "': " + e.getMessage() );
                return Optional.empty();
            }
        }

        @Override
        public String toString()
        {
            return "file:"+file.getAbsolutePath();
        }
    }

    public Blacklist getBlacklist()
    {
        return blacklist;
    }

    public void load() throws IOException {

        final Optional<IResource> resource = getResource(true);
        if ( resource.isEmpty())
        {
            LOG.info( "Using built-in configuration.");
            return;
        }
        LOG.info( "Loading configuration from {}", resource );
        load( resource.get() );
        LOG.info( "Client artifact update mode: {}", getClientArtifactUpdateMode() );
    }

    public static Optional<IResource> getResource(boolean verbose) throws IOException
    {
        final boolean fromSystemProperty;
        String location = System.getProperty( CONFIG_FILE_LOCATION_SYS_PROPERTY );
        if ( location != null ) {
            if ( verbose )
            {
                LOG.info( "Configuration (system property '" + CONFIG_FILE_LOCATION_SYS_PROPERTY + "') = " + location );
            }
            fromSystemProperty = true;
            if ( ! location.toLowerCase().startsWith("file:") )
            {
                location = "file:" + location;
            }
        } else {
            location = DEFAULT_CONFIG_FILE_LOCATION;
            if ( verbose )
            {
                LOG.info( "Configuration (default location) " + location );
            }
            fromSystemProperty = false;
        }

        final IResource resource;
        if ( location.toLowerCase().startsWith("file:" ) ) {
            resource = new FileResource( new File( location.substring( "file:".length() ) ) );
            if ( ! resource.exists() ) {
                if ( ! fromSystemProperty ) {
                    if ( verbose )
                    {
                        LOG.info( "No configuration found in default location (" + DEFAULT_CONFIG_FILE_LOCATION + ")" );
                    }
                    return Optional.empty();
                }
            }
        } else if ( location.toLowerCase().startsWith("classpath:" ) ) {
            resource = new ClasspathResource( location.substring( "classpath:".length() ) );
            if ( ! resource.exists() ) {
                if ( fromSystemProperty ) {
                    LOG.error("Failed to load configuration from '"+location+"'");
                    throw new IOException("Failed to load configuration from '"+location+"'");
                }
                return Optional.empty();
            }
        } else {
            final String msg = "Invalid config file location '" + location + "', needs to be prefixed with 'classpath:' or 'file:'";
            LOG.error( msg );
            throw new IOException( msg );
        }
        return Optional.of( resource );
    }

    public void load(IResource resource) throws IOException
    {
        try ( InputStream in = resource.open() ) {
            final Properties props = new Properties();
            props.load( in );

            final String ds = props.getProperty( "dataStorage" );
            this.dataStore = ds != null ? new File(ds): null;
            this.minUpdateDelayAfterFailure = getDuration( props, "updateDelayAfterFailure", minUpdateDelayAfterFailure );
            this.minUpdateDelayAfterSuccess = getDuration( props, "updateDelayAfterSuccess", minUpdateDelayAfterSuccess );
            this.bgUpdateCheckInterval = getDuration( props, "bgUpdateCheckInterval" , bgUpdateCheckInterval );

            final String configKey = "clientRequestArtifactUpdateMode";
            final String propValue = props.getProperty( configKey,
                ClientRequestArtifactUpdateMode.ASYNC_ONLY_WHEN_SUCCESSFUL.configLiteral );
            final Optional<ClientRequestArtifactUpdateMode>
                clientRequestArtifactUpdateMode =
                ClientRequestArtifactUpdateMode.fromString( propValue );
            if ( clientRequestArtifactUpdateMode.isEmpty() ) {
                final String msg = "Unrecognized value '" + propValue + "' for '" + configKey + "' configuration property - valid values are "
                                   + Arrays.stream( ClientRequestArtifactUpdateMode.values() ).map(x->"'"+x.configLiteral+"'")
                                       .collect( Collectors.joining(", "));
                LOG.error( msg );
                throw new RuntimeException(msg);
            } else {
                this.clientArtifactUpdateMode = clientRequestArtifactUpdateMode.get();
            }

            final Duration bgUpdateDelayMin = getDuration( props, "bgUpdateDelayMin" , this.backgroundUpdateDelayWindowInMinutes.min() );
            final Duration bgUpdateDelayMax = getDuration( props, "bgUpdateDelayMax" , this.backgroundUpdateDelayWindowInMinutes.max() );
            setBackgroundUpdateDelayWindow( new DurationRange( bgUpdateDelayMin, bgUpdateDelayMax ) );

            LOG.info( "data storage= " + dataStore);
            LOG.info( "min. update delay after failure = " + minUpdateDelayAfterFailure );
            LOG.info( "min. update delay after success = " + minUpdateDelayAfterSuccess );
            LOG.info( "bg update check interval = " + bgUpdateCheckInterval );

            final Blacklist newBlacklist = new Blacklist();
            final String blacklistedGroupIds = props.getProperty("blacklistedGroupIds");
            if ( StringUtils.isNotBlank( blacklistedGroupIds ) )
            {
                LOG.info( "Blacklisted group IDs: " + blacklistedGroupIds );
                final String[] ids = blacklistedGroupIds.split( "[, ]" );
                for ( final String id : ids )
                {
                    newBlacklist.addIgnoredVersion( id, ".*", Blacklist.VersionMatcher.REGEX );
                }
                this.blacklist = newBlacklist;
            }
        }
    }

    private String getProperty(Properties props, String property) {
        String result = System.getProperty( "versionTracker."+property );
        if ( result != null ) {
            return result;
        }
        return props.getProperty( property );
    }

    private Duration getDuration(Properties props, String key, Duration defaultValue) {
        final String value = getProperty( props, key );
        if ( value == null ) {
            return defaultValue;
        }
        return parseDurationString( value );
    }

    public void setBlacklist(Blacklist blacklist)
    {
        Validate.notNull( blacklist, "blacklist must not be null" );
        this.blacklist = blacklist;
    }

    public Duration getMinUpdateDelayAfterFailure()
    {
        return minUpdateDelayAfterFailure;
    }

    public void setMinUpdateDelayAfterFailure(Duration minUpdateDelayAfterFailure)
    {
        Validate.notNull( minUpdateDelayAfterFailure, "minUpdateDelayAfterFailure must not be null" );
        Validate.isTrue( minUpdateDelayAfterFailure.compareTo( Duration.ZERO ) > 0 );
        this.minUpdateDelayAfterFailure = minUpdateDelayAfterFailure;
    }

    public Duration getMinUpdateDelayAfterSuccess()
    {
        return minUpdateDelayAfterSuccess;
    }

    public void setMinUpdateDelayAfterSuccess(Duration minUpdateDelayAfterSuccess)
    {
        Validate.notNull( minUpdateDelayAfterSuccess, "minUpdateDelayAfterSuccess must not be null" );
        Validate.isTrue( minUpdateDelayAfterSuccess.compareTo( Duration.ZERO ) > 0 );
        this.minUpdateDelayAfterSuccess = minUpdateDelayAfterSuccess;
    }

    public Duration getBgUpdateCheckInterval()
    {
        return bgUpdateCheckInterval;
    }

    public void setBgUpdateCheckInterval(Duration bgUpdateCheckInterval)
    {
        Validate.notNull( bgUpdateCheckInterval, "bgUpdateCheckInterval must not be null" );
        this.bgUpdateCheckInterval = bgUpdateCheckInterval;
    }

    public static Duration parseDurationString(String s) {
        Validate.notBlank( s, "s must not be null or blank");
        final Pattern p = Pattern.compile( "^([0-9]+)([smhd])$" , Pattern.CASE_INSENSITIVE);
        final Matcher m = p.matcher( s.trim() );
        if ( ! m.matches() ) {
            throw new IllegalArgumentException( "Invalid syntax" );
        }
        int seconds = Integer.parseInt(m.group(1));
        switch( m.group(2) ) {
            case "d":
                seconds *= 24;
            case "h":
                seconds *= 60;
            case "m":
                seconds *= 60;
            case "s":
                break;
            default: throw new RuntimeException( "Unhandled switch/case: " + m.group( 2 ) );
        }
        return Duration.ofSeconds( seconds );
    }

    public void setDataStorageFile(File dataStore)
    {
        this.dataStore = dataStore;
    }

    public Optional<File> getDataStorageFile()
    {
        return Optional.ofNullable( dataStore );
    }

    public DurationRange getBackgroundUpdateDelayWindowInMinutes()
    {
        return backgroundUpdateDelayWindowInMinutes;
    }

    public void setBackgroundUpdateDelayWindow(DurationRange backgroundUpdateDelayWindowInMinutes)
    {
        Validate.notNull( backgroundUpdateDelayWindowInMinutes, "backgroundUpdateDelayWindowInMinutes must not be null" );
        this.backgroundUpdateDelayWindowInMinutes = backgroundUpdateDelayWindowInMinutes;
    }

    public void setClientArtifactUpdateMode(ClientRequestArtifactUpdateMode clientArtifactUpdateMode)
    {
        this.clientArtifactUpdateMode = clientArtifactUpdateMode;
    }

    public ClientRequestArtifactUpdateMode getClientArtifactUpdateMode()
    {
        return clientArtifactUpdateMode;
    }
}
