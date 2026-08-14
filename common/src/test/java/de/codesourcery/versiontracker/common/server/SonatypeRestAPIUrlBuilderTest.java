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
    /** row count the no-arg builder constructor requests */
    private static final int ROWS = SonatypeRestAPIUrlBuilder.DEFAULT_MAX_RESULTS_PER_REQUEST;

    @Test
    void testQuerySingleVersion() throws IOException
    {
        assertEquals( url( "https://central.sonatype.com/solrsearch/select?q=g%3Agroup+AND+a%3Aartifact+AND+v%3Aversion&rows=" + ROWS + "&wt=json" ),
            new SonatypeRestAPIUrlBuilder()
            .artifactId( "artifact" )
            .groupId( "group" )
            .version( "version" )
            .build() );
    }

    @Test
    void testQuerySingleVersionWithClassifier() throws IOException
    {
        assertEquals( url( "https://central.sonatype.com/solrsearch/select?q=g%3Agroup+AND+a%3Aartifact+AND+l%3Aclassifier+AND+v%3Aversion&rows=" + ROWS + "&wt=json" ),
            new SonatypeRestAPIUrlBuilder()
            .artifactId( "artifact" )
            .groupId( "group" )
            .classifier( "classifier" )
            .version( "version" )
            .build() );
    }

    @Test
    void testQueryAllVersions() throws IOException
    {
        assertEquals( url( "https://central.sonatype.com/solrsearch/select?q=g%3Agroup+AND+a%3Aartifact&core=gav&rows=" + ROWS + "&wt=json" ),
            new SonatypeRestAPIUrlBuilder()
            .artifactId( "artifact" )
            .groupId( "group" )
            .returnAllResults()
            .build() );
    }

    @Test
    void testQueryAllVersionsWithClassifier() throws IOException
    {
        assertEquals( url( "https://central.sonatype.com/solrsearch/select?q=g%3Agroup+AND+a%3Aartifact+AND+l%3Aclassifier&core=gav&rows=" + ROWS + "&wt=json" ),
            new SonatypeRestAPIUrlBuilder()
            .artifactId( "artifact" )
            .groupId( "group" )
            .classifier( "classifier" )
            .returnAllResults()
            .build() );
    }

    @Test
    void testBlankClassifierIsIgnored() throws IOException
    {
        final URL expected = url( "https://central.sonatype.com/solrsearch/select?q=g%3Agroup+AND+a%3Aartifact+AND+v%3Aversion&rows=" + ROWS + "&wt=json" );
        for ( final String classifier : new String[] { null, "", "   " } )
        {
            assertEquals( expected,
                new SonatypeRestAPIUrlBuilder()
                .artifactId( "artifact" )
                .groupId( "group" )
                .classifier( classifier )
                .version( "version" )
                .build(), "classifier '" + classifier + "' should have been ignored" );
        }
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