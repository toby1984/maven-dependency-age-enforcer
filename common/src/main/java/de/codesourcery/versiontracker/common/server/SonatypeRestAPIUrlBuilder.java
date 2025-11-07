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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Builder to construct HTTP GET URLs for calling the Sonatype REST API.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public final class SonatypeRestAPIUrlBuilder
{
    private static final Logger LOG = LogManager.getLogger( SonatypeRestAPIUrlBuilder.class );

    private static final int DEFAULT_MAX_RESULT_COUNT = 300;

    // internal parameter names
    private enum InternalParameter {
       PARAM_START_OFFSET,
       PARAM_MAX_RESULT_COUNT,
       PARAM_RETURN_ALL_RESULTS,
       PARAM_VERSION,
       PARAM_CLASSIFIER,
       PARAM_ARTIFACT_ID,
       PARAM_GROUP_ID,
    }

    private final Map<InternalParameter, Object> args = new HashMap<>();

    private final String baseURL;

    public SonatypeRestAPIUrlBuilder()
    {
        this( DEFAULT_MAX_RESULT_COUNT );
    }

    /**
     * Create instance.
     *
     * @param rowCount how many results the REST API should return at most. Most be greater than zero.
     */
    public SonatypeRestAPIUrlBuilder(int rowCount) {
        this( MavenCentralVersionProvider.DEFAULT_SONATYPE_REST_API_BASE_URL, rowCount );
    }

    public SonatypeRestAPIUrlBuilder(String baseURL) {
        this( baseURL, DEFAULT_MAX_RESULT_COUNT );
    }

    public SonatypeRestAPIUrlBuilder(String baseURL, int maxResultCount)
    {
        Validate.notBlank( baseURL, "baseURL must not be null or blank");
        Validate.isTrue( maxResultCount > 0 );
        this.baseURL = baseURL;
        args.put( InternalParameter.PARAM_MAX_RESULT_COUNT, maxResultCount );
    }


    public SonatypeRestAPIUrlBuilder artifactId(String id)
    {
        Validate.notBlank( id, "Artifact ID must not be null" );
        return set( InternalParameter.PARAM_ARTIFACT_ID, id );
    }

    public SonatypeRestAPIUrlBuilder startOffset(int offset)
    {
        Validate.isTrue( offset >= 0 );
        return set( InternalParameter.PARAM_START_OFFSET, offset, false );
    }

    public SonatypeRestAPIUrlBuilder groupId(String id)
    {
        Validate.notBlank( id, "group ID must not be null or blank" );
        return set( InternalParameter.PARAM_GROUP_ID, id );
    }

    public SonatypeRestAPIUrlBuilder version(String id)
    {
        Validate.notBlank( id, "version must not be null or blank" );
        return set( InternalParameter.PARAM_VERSION, id );
    }

    public SonatypeRestAPIUrlBuilder classifier(String id)
    {
        if ( id != null )
        {
            set( InternalParameter.PARAM_CLASSIFIER, id );
        }
        return this;
    }

    public SonatypeRestAPIUrlBuilder returnAllResults()
    {
        return set( InternalParameter.PARAM_RETURN_ALL_RESULTS, true );
    }

    private SonatypeRestAPIUrlBuilder set(InternalParameter param, Object value)
    {
        return set( param, value, true );
    }

    private SonatypeRestAPIUrlBuilder set(InternalParameter param, Object value, boolean failIfAlreadySet)
    {
        Validate.notNull( param, "param must not be null" );
        Validate.notNull( value, "value must not be null" );
        if ( failIfAlreadySet && args.containsKey( param ) )
        {
            throw new IllegalStateException( "Parameter '" + param + "' already set to '" + args.get( param ) + "' ? " );
        }
        this.args.put( param, value );
        return this;
    }

    private String getString(InternalParameter param)
    {
        Validate.notNull( param, "param must not be null" );
        return (String) args.get( param );
    }

    private Boolean getBoolean(InternalParameter param)
    {
        Validate.notNull( param, "param must not be null" );
        return (Boolean) args.get( param );
    }

    private Integer getInteger(InternalParameter param)
    {
        Validate.notNull( param, "param must not be null" );
        return (Integer) args.get( param );
    }

    public URL build() throws IOException
    {
        final String groupId = getString( InternalParameter.PARAM_GROUP_ID );
        final String artifactId = getString( InternalParameter.PARAM_ARTIFACT_ID );

        Validate.notBlank( groupId, "groupId must not be null or blank" );
        Validate.notBlank( artifactId, "artifactId must not be null or blank" );

        final Map<MavenCentralVersionProvider.HttpParam, String> params = new HashMap<>();

        String query = "g:" + groupId + " AND a:" + artifactId;
        final String classifier = getString( InternalParameter.PARAM_CLASSIFIER );
        if ( StringUtils.isNotBlank( classifier ) )
        {
            query += "AND l:" + classifier;
        }

        final String version = getString( InternalParameter.PARAM_VERSION );
        final Boolean returnAllVersions = getBoolean( InternalParameter.PARAM_RETURN_ALL_RESULTS );
        if ( returnAllVersions != null && returnAllVersions )
        {
            Validate.isTrue( StringUtils.isBlank( version ), "version must be null or blank when returnAllVersions == true" );
            params.put( MavenCentralVersionProvider.HttpParam.OPT_RETURN_ALL_VERSION, null );
        }
        else
        {
            Validate.notBlank( version, "version must not be null or blank when returnAllVersions == false" );
            query += " AND v: " + version;
        }
        params.put( MavenCentralVersionProvider.HttpParam.QUERY, query );

        final Integer startOffset = getInteger( InternalParameter.PARAM_START_OFFSET );
        if ( startOffset != null )
        {
            params.put( MavenCentralVersionProvider.HttpParam.START_OFFSET, Integer.toString( startOffset ) );
        }

        final int rowCount = getInteger( InternalParameter.PARAM_MAX_RESULT_COUNT );
        params.put( MavenCentralVersionProvider.HttpParam.MAX_RESULT_COUNT, Integer.toString( rowCount ) );
        params.put( MavenCentralVersionProvider.HttpParam.RESULT_TYPE, "json" );
        return createURL( params );
    }

    private URL createURL(Map<MavenCentralVersionProvider.HttpParam, String> httpGetParams) throws IOException
    {
        Validate.notEmpty( httpGetParams );

        final String queryString = "?"
            + httpGetParams.entrySet()
            .stream()
            .sorted( Comparator.comparingInt( a -> a.getKey().order ) )
            .map( x -> {
                final String value;
                if ( x.getValue() != null )
                {
                    value = x.getValue();
                }
                else if ( x.getKey().value != null )
                {
                    value = x.getKey().value;
                }
                else
                {
                    throw new IllegalArgumentException( "No value for HTTP get parameter '" + x.getKey() + "'" );
                }
                return x.getKey().literal + "=" + URLEncoder.encode( value, StandardCharsets.UTF_8 );

            } )
            .collect( Collectors.joining( "&" ) );

        // strip any trailing slashes as sonatype API server
        // is picky about this and will send a "403 - Forbidden"
        // if we don't
        String strippedBaseUrl = baseURL;
        while ( strippedBaseUrl.endsWith( "/" ) ) {
            strippedBaseUrl = strippedBaseUrl.substring( 0, strippedBaseUrl.length() - 1 );
        }

        final String urlString = strippedBaseUrl + queryString;
        try
        {
            return new URI( urlString ).toURL();
        }
        catch( URISyntaxException e )
        {
            LOG.error( "getReleaseDateNew(): Invalid URL ? " + urlString, e );
            throw new IOException( e );
        }
    }
}
