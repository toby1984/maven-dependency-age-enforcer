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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.logging.log4j.LogManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.Blacklist;
import de.codesourcery.versiontracker.common.JSONHelper;
import de.codesourcery.versiontracker.common.QueryRequest;
import de.codesourcery.versiontracker.common.QueryResponse;

/**
 * API client that talks to the endpoint using HTTP + JSON.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class JSONApiClient extends AbstractAPIClient
{
	private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger(JSONApiClient.class);
	
	private String endpointUrl;

	public JSONApiClient(String endpointUrl) {
		this.endpointUrl = endpointUrl;
	}
	
	@Override
	public List<ArtifactResponse> query(List<Artifact> artifacts,Blacklist blacklist) throws IOException 
	{
		final QueryRequest request = toQueryRequest(artifacts, blacklist);
		final ObjectMapper mapper = JSONHelper.newObjectMapper();
		final String jsonRequest = mapper.writeValueAsString(request);
		final String jsonResponse = doPost( jsonRequest );
		final QueryResponse response = mapper.readValue(jsonResponse,QueryResponse.class);		
		return response.artifacts;
	}
	
	private String doPost(String json) throws ClientProtocolException, IOException 
	{
	    if ( debugMode ) {
	        System.out.println("REQUEST: \n"+json+"\n");
	    }
	    if ( LOG.isDebugEnabled() ) {
	        LOG.debug("doPost(): Sending request to "+endpointUrl);
	        LOG.debug("doPost(): REQUEST: \n=====\n"+json+"\n=======");
	    }
		final HttpClient httpclient = HttpClients.createDefault();

		final HttpPost httppost = new HttpPost( endpointUrl );
		httppost.setEntity(new StringEntity(json));

		final HttpResponse response = httpclient.execute(httppost);
		final HttpEntity entity = response.getEntity();

	    try ( InputStream instream = entity.getContent() ) 
	    {
	    	final String resp = IOUtils.readLines( instream, "UTF8" ).stream().collect(Collectors.joining());
	    	
	        if ( debugMode ) {
	            System.out.println("RESPONSE: \n"+resp+"\n");
	        }
	        
	    	if ( LOG.isDebugEnabled() ) {
	    	    LOG.debug("doPost(): RESPONSE: \n=====\n"+resp+"\n=======");
	    	}
	    	return resp;
	    }
	}

    @Override
    public void close() throws Exception
    {
    }
}