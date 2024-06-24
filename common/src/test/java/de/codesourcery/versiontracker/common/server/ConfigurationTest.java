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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest
{
    private Path tmpFile;


    @BeforeEach
    void setup() throws IOException
    {
        tmpFile = Files.createTempFile( "prefix", "suffix" );
    }

    @AfterEach
    void tearDown() throws IOException
    {
        Files.delete( tmpFile );
    }

    @Test
    void testLoadFromFile() throws IOException
    {
        final Configuration c = new Configuration();

        writeTmpFile( """
            blacklistedGroupIds=com.voipfuture,org.apache.tomcat
            updateDelayAfterFailure=1m
            updateDelayAfterSuccess=10m
            bgUpdateCheckInterval=15m
            """ );

        System.setProperty( Configuration.CONFIG_FILE_LOCATION_SYS_PROPERTY , tmpFile.toFile().getAbsolutePath() );
        try
        {
            c.load();
            assertEquals( Duration.ofMinutes( 1 ), c.getMinUpdateDelayAfterFailure() );
            assertEquals( Duration.ofMinutes( 10 ), c.getMinUpdateDelayAfterSuccess() );
            assertEquals( Duration.ofMinutes( 15 ), c.getBgUpdateCheckInterval() );
            assertTrue( c.getBlacklist().isAllVersionsBlacklisted( "com.voipfuture.test", "blubb" ) );
            assertTrue( c.getBlacklist().isAllVersionsBlacklisted( "org.apache.tomcat", "blah" ) );
            assertFalse( c.getBlacklist().isAllVersionsBlacklisted( "org.apache.mina", "blah" ) );
        } finally {
            System.clearProperty( Configuration.CONFIG_FILE_LOCATION_SYS_PROPERTY );
        }

    }

    private void writeTmpFile(String content) throws IOException
    {
        try ( final OutputStream out = Files.newOutputStream( tmpFile ) ) {
            out.write( content.getBytes() );
        }
    }
}