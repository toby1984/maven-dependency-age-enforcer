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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SonatypeRestAPIUrlBuilderTest
{
    @Test
    void testQuerySingleVersion() throws IOException
    {
        assertEquals( url( "https://search.maven.org/solrsearch/select?q=g%3Agroup+AND+a%3Aartifact+AND+v%3A+version&rows=300&wt=json" ),
            new SonatypeRestAPIUrlBuilder()
            .artifactId( "artifact" )
            .groupId( "group" )
            .version( "version" )
            .build() );
    }

    @Test
    void testQueryAllVersions() throws IOException
    {
        assertEquals( url( "https://search.maven.org/solrsearch/select?q=g%3Agroup+AND+a%3Aartifact&core=gav&rows=300&wt=json" ),
            new SonatypeRestAPIUrlBuilder()
            .artifactId( "artifact" )
            .groupId( "group" )
            .returnAllResults()
            .build() );
    }

    private static URL url(String s) {
        try
        {
            return URI.create(s).toURL();
        }
        catch( MalformedURLException e )
        {
            throw new RuntimeException( e );
        }
    }
}