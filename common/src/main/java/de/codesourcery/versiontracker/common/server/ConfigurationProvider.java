package de.codesourcery.versiontracker.common.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class ConfigurationProvider implements AutoCloseable
{
    private static final Logger LOG = LogManager.getLogger( ConfigurationProvider.class );
    private final AtomicReference<Configuration> configuration = new AtomicReference<>();

    private Thread bgThread;
    private volatile boolean terminate;

    private final Object SLEEP_LOCK = new Object();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private volatile Duration reloadCheckInterval = Duration.ofSeconds( 1 );

    public ConfigurationProvider() {
    }

    private synchronized void startBgThread()
    {
        if ( ! terminate && ( bgThread == null || ! bgThread.isAlive() ) )
        {
            bgThread = new Thread() {

                {
                    setName( "config-reload-thread" );
                    setDaemon( true );
                }

                @Override
                public void run()
                {
                    try
                    {
                        while ( ! terminate )
                        {
                            synchronized( SLEEP_LOCK )
                            {
                                try
                                {
                                    if ( ! terminate )
                                    {
                                        SLEEP_LOCK.wait( reloadCheckInterval.toMillis() );
                                    }
                                    if ( terminate ) {
                                        break;
                                    }
                                }
                                catch( Exception e )
                                {
                                    // don't care
                                }
                            }
                            try
                            {
                                LOG.debug( "Checking whether configuration has changed..." );
                                final Optional<Configuration.IResource> resource = Configuration.getResource( false );
                                if ( resource.isPresent() ) {
                                    final Optional<ZonedDateTime> lastChangeDate = resource.get().lastChangeDate();
                                    final Configuration currentConfig = configuration.get();
                                    if ( lastChangeDate.isPresent() && currentConfig != null && currentConfig.timestamp.isBefore(( lastChangeDate.get() ) ) )
                                    {
                                        LOG.info( "Configuration change detected, reloading..." );
                                        final Configuration newConfig = new Configuration();
                                        newConfig.load();
                                        if ( configuration.compareAndSet( currentConfig, newConfig ) ) {
                                            LOG.info( "Configuration reloaded");
                                        } else {
                                            LOG.warn( "Configuration change in the meantime?");
                                        }
                                    }
                                }
                            }
                            catch( IOException e )
                            {
                                // don't care
                            }
                        }
                    }
                    finally {
                        shutdownLatch.countDown();
                    }
                }
            };
            bgThread.start();
        }
    }

    @Override
    public synchronized void close() throws InterruptedException
    {
        terminate = true;
        synchronized( SLEEP_LOCK ) {
            SLEEP_LOCK.notifyAll();;
        }
        if ( bgThread != null && bgThread.isAlive() ) {
            shutdownLatch.await();
        }
    }

    public synchronized Configuration getConfiguration()
    {
        startBgThread();

        if ( configuration.get() == null ) {
            final Configuration c = new Configuration();
            try
            {
                c.load();
                configuration.set( c );
            }
            catch( IOException e )
            {
                throw new UncheckedIOException( e );
            }
        }
        return configuration.get();
    }
}