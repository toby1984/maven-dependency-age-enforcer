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
package de.codesourcery.versiontracker.client.api.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.codesourcery.versiontracker.client.api.AbstractAPIClient;
import de.codesourcery.versiontracker.client.api.IAPIClient;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.BinarySerializer;
import de.codesourcery.versiontracker.common.BinarySerializer.IBuffer;
import de.codesourcery.versiontracker.common.Blacklist;
import de.codesourcery.versiontracker.common.JSONHelper;
import de.codesourcery.versiontracker.common.QueryRequest;
import de.codesourcery.versiontracker.common.QueryResponse;
import de.codesourcery.versiontracker.common.Utils;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.logging.log4j.LogManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * API client that talks to the endpoint using HTTP + JSON.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class RemoteApiClient extends AbstractAPIClient
{
    private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger(RemoteApiClient.class);

    private final Protocol protocol;
    private final String endpointUrl;

    private final Object CLIENT_LOCK = new Object();
    
    // @GuardedBy(CLIENT_LOCK)
    private CloseableHttpClient client;

    public RemoteApiClient(String endpointUrl,Protocol protocol) {
        this.endpointUrl = endpointUrl;
        this.protocol = protocol; 
    }

    private synchronized CloseableHttpClient client() 
    {
        synchronized (CLIENT_LOCK) 
        {
            if ( client == null ) {
                client = HttpClients.createDefault();
            }
            return client;
        }
    }

    @Override
    public List<ArtifactResponse> query(List<Artifact> artifacts,Blacklist blacklist) throws IOException 
    {
        final QueryRequest request = toQueryRequest(artifacts, blacklist);
        final QueryResponse response;
        if ( protocol == Protocol.JSON ) {
            final ObjectMapper mapper = JSONHelper.newObjectMapper();
            final String jsonRequest = mapper.writeValueAsString(request);
            final String jsonResponse = doPost( jsonRequest );
            response = mapper.readValue(jsonResponse,QueryResponse.class);		
        } 
        else if ( protocol == Protocol.BINARY ) 
        {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final IBuffer outBuffer = IBuffer.wrap( out );

            final BinarySerializer outSerializer = new BinarySerializer( outBuffer );
            request.serialize( outSerializer );
            final byte[] binaryResponse = doPost( out.toByteArray() );

            final ByteArrayInputStream in = new ByteArrayInputStream(binaryResponse);
            final IBuffer inBuffer = IBuffer.wrap( in );
            final BinarySerializer inSerializer = new BinarySerializer( inBuffer );
            response = (QueryResponse) QueryResponse.deserialize( inSerializer );
        } else {
            throw new RuntimeException("Internal error, unhandled protocol "+protocol);
        }
        return response.artifacts;
    }

    private String doPost(String json) throws IOException
    {
        if ( debugMode ) {
            System.out.println("REQUEST: \n"+json+"\n");
        }
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("doPost(): Sending request to "+endpointUrl);
            LOG.debug("doPost(): REQUEST: \n=====\n"+json+"\n=======");
        }

        final byte[] data = doPost( json.getBytes( StandardCharsets.UTF_8 ), true );
        final String resp = new String(data, StandardCharsets.UTF_8 );
        if ( debugMode ) {
            System.out.println("RESPONSE: \n"+resp+"\n");
        }

        if ( LOG.isDebugEnabled() ) {
            LOG.debug("doPost(): RESPONSE: \n=====\n"+resp+"\n=======");
        }       
        return resp;
    }

    private byte[] doPost(byte[] input) throws IOException {
        return doPost(input,false);
    }

    private byte[] doPost(byte[] input,boolean debugPrinted) throws IOException
    {
        final HttpPost httppost = new HttpPost( endpointUrl );

        final String mimeType = protocol.mimeType;
        httppost.setHeader( "Content-Type", mimeType );
        httppost.setHeader( "Accept", mimeType );

        // add protocol identifier as first byte
        final byte[] tmp = IAPIClient.prependProtocolIdentifier(input,protocol);
        httppost.setEntity(new ByteArrayEntity(tmp, ContentType.create( protocol.mimeType ) ));

        if ( ! debugPrinted ) 
        {
            if ( debugMode ) {
                System.out.println("REQUEST: \n"+Utils.toHex(tmp)+"\n");
            }
            if ( LOG.isDebugEnabled() ) {
                LOG.debug("doPost(): Sending request to "+endpointUrl);
                LOG.debug("doPost(): REQUEST: \n=====\n"+Utils.toHex(tmp)+"\n=======");
            }
        }

        try ( final CloseableHttpResponse response = client().execute( httppost ) ) {
            final HttpEntity entity = response.getEntity();

            final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            try ( InputStream instream = entity.getContent() ) {
                final byte[] buffer = new byte[10 * 1024];
                int len;
                while ((len = instream.read( buffer )) > 0) {
                    byteOut.write( buffer, 0, len );
                }
            }

            if ( response.getCode() != HttpStatus.SC_OK ) {
                throw new IOException( "Server returned an error, HTTP " + response.getCode() + " " + response.getReasonPhrase() + "\n\n" + byteOut.toString( StandardCharsets.UTF_8 ) );
            }

            final byte[] resp = byteOut.toByteArray();
            if ( !debugPrinted ) {
                if ( debugMode ) {
                    System.out.println( "RESPONSE: \n" + Utils.toHex( resp ) + "\n" );
                }

                if ( LOG.isDebugEnabled() ) {
                    LOG.debug( "doPost(): RESPONSE: \n=====\n" + Utils.toHex( resp ) + "\n=======" );
                }
            }
            return resp;
        }
    }

    @Override
    public void close() throws Exception
    {
        synchronized (CLIENT_LOCK) {
            if ( client != null ) {
                try {
                    client.close();
                }
                catch(Error ex) {
                    /*
                     * Hack around weird crash upon close() that happens with Apache httpcomponents >= 5.2.3 and (at least) Maven 3.9.1,
                     * maybe related to Plexus classpath container stuff and Maven still being on httpcomponents 4.x
                     *
                     * Exception in thread "Thread-1" java.lang.NoClassDefFoundError: org/apache/hc/core5/io/CloseMode
                     *         at org.apache.hc.client5.http.impl.classic.InternalHttpClient.close(InternalHttpClient.java:184)
                     *         at de.codesourcery.versiontracker.client.api.remote.RemoteApiClient.close(RemoteApiClient.java:185)
                     *         at de.codesourcery.versiontracker.enforcerrule.DependencyAgeRule.lambda$getRemoteAPIClient$1(DependencyAgeRule.java:676)
                     *         at java.base/java.lang.Thread.run(Thread.java:1583)
                     * Caused by: java.lang.ClassNotFoundException: org.apache.hc.core5.io.CloseMode
                     *         at org.codehaus.plexus.classworlds.strategy.SelfFirstStrategy.loadClass(SelfFirstStrategy.java:50)
                     *         at org.codehaus.plexus.classworlds.realm.ClassRealm.unsynchronizedLoadClass(ClassRealm.java:271)
                     *         at org.codehaus.plexus.classworlds.realm.ClassRealm.loadClass(ClassRealm.java:247)
                     *         at org.codehaus.plexus.classworlds.realm.ClassRealm.loadClass(ClassRealm.java:239)
                     *         ... 4 more
                     */

                    if ( !(ex instanceof NoClassDefFoundError err) || !(err.getCause() instanceof ClassNotFoundException e2) ||
                        e2.getMessage() == null || !e2.getMessage().contains( "org.apache.hc.core5.io.CloseMode" ) ) {
                            throw ex;
                        }
                }
                finally {
                    client = null;
                }
            }
        }
    }
}