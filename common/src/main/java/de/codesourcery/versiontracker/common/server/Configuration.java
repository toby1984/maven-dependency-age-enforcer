package de.codesourcery.versiontracker.common.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import de.codesourcery.versiontracker.common.Blacklist;

public class Configuration
{
    private static final Logger LOG = LogManager.getLogger( Configuration.class );
    private static final String DEFAULT_CONFIG_FILE_LOCATION = "classpath:/versionTracker.json";
    private static final String CONFIG_FILE_LOCATION_SYS_PROPERTY = "versionTracker.configFile";

    private Blacklist blacklist = new Blacklist();
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

    public Blacklist getBlacklist()
    {
        return blacklist;
    }

    public void load() throws IOException {

        final boolean fromSystemProperty;
        String location = System.getProperty( CONFIG_FILE_LOCATION_SYS_PROPERTY );
        if ( location != null ) {
            LOG.info( "Configuration (system property '" + CONFIG_FILE_LOCATION_SYS_PROPERTY + "') = " + location );
            fromSystemProperty = true;
            if ( ! location.toLowerCase().startsWith("file:") )
            {
                location = "file:" + location;
            }
        } else {
            location = DEFAULT_CONFIG_FILE_LOCATION;
            LOG.info( "Configuration (default location) " + location );
            fromSystemProperty = false;
        }

        final InputStream in;
        if ( location.toLowerCase().startsWith("file:" ) ) {
            final File f = new File(location.substring( "file:".length() ));
            if ( ! f.exists() ) {
                if ( ! fromSystemProperty ) {
                    LOG.info( "No configuration found in default location (" + DEFAULT_CONFIG_FILE_LOCATION + ")" );
                    return;
                }
            }
            in = new FileInputStream( f );
        } else if ( location.toLowerCase().startsWith("classpath:" ) ) {
            in = Configuration.class.getResourceAsStream( location.substring( "classpath:".length() ) );
            if ( in == null ) {
                if ( fromSystemProperty ) {
                    LOG.error("Failed to load configuration from '"+location+"'");
                    throw new IOException("Failed to load configuration from '"+location+"'");
                }
                LOG.info("Using built-in configuration.");
                return;
            }
        } else {
            final String msg = "Invalid config file location '" + location + "', needs to be prefixed with 'classpath:' or 'file:'";
            LOG.error( msg );
            throw new IOException( msg );
        }
        try ( in ) {
            final Properties props = new Properties();
            props.load( in );

            this.minUpdateDelayAfterFailure = getDuration( props, "updateDelayAfterFailure", minUpdateDelayAfterFailure );
            this.minUpdateDelayAfterSuccess = getDuration( props, "updateDelayAfterSuccess", minUpdateDelayAfterSuccess );
            this.bgUpdateCheckInterval = getDuration( props, "bgUpdateCheckInterval", bgUpdateCheckInterval );

            this.blacklist.clear();
            final String blacklistedGroupIds = props.getProperty("blacklistedGroupIds");
            if ( StringUtils.isNotBlank( blacklistedGroupIds ) )
            {
                final String[] ids = blacklistedGroupIds.split( "[, ]" );
                for ( final String id : ids )
                {
                    this.blacklist.addIgnoredVersion( id, ".*", Blacklist.VersionMatcher.REGEX );
                }
            }
        }
    }

    private String getProperty(String overrideProp, String defaultProp) {
        String result = System.getProperty( overrideProp );
        if ( result != null ) {
            return result;
        }
        // TODO: Implement me!
    }

    private Duration getDuration(Properties props, String key, Duration defaultValue) {
        final String value = props.getProperty( key );
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
}
