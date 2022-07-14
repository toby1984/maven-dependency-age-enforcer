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
package de.codesourcery.versiontracker.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.BinarySerializer;
import de.codesourcery.versiontracker.common.BinarySerializer.IBuffer;
import de.codesourcery.versiontracker.common.Blacklist;
import de.codesourcery.versiontracker.common.JSONHelper;
import de.codesourcery.versiontracker.common.QueryRequest;
import de.codesourcery.versiontracker.common.QueryResponse;
import de.codesourcery.versiontracker.common.Utils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
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

        final byte[] tmp = IAPIClient.toWireFormat(input,protocol);
        httppost.setEntity(new ByteArrayEntity(tmp));

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

        final HttpResponse response = client().execute(httppost);
        final HttpEntity entity = response.getEntity();

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        try ( InputStream instream = entity.getContent() ) 
        {
            final byte[] buffer = new byte[10*1024];
            int len;
            while ( ( len = instream.read(buffer) ) > 0 ) {
                byteOut.write(buffer,0,len);
            }
        }
        final byte[] resp = byteOut.toByteArray();
        if ( ! debugPrinted ) 
        {
            if ( debugMode ) {
                System.out.println("RESPONSE: \n"+Utils.toHex(resp)+"\n");
            }

            if ( LOG.isDebugEnabled() ) {
                LOG.debug("doPost(): RESPONSE: \n=====\n"+Utils.toHex(resp)+"\n=======");
            } 
        }
        return resp;
    }

    @Override
    public void close() throws Exception
    {
        synchronized (CLIENT_LOCK) {
            if ( client != null ) {
                try {
                    client.close();
                } finally {
                    client = null;
                }
            }
        }
    }
}