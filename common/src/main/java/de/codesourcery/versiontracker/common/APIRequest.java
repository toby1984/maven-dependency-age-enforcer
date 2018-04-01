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
package de.codesourcery.versiontracker.common;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonProperty;

import de.codesourcery.versiontracker.client.IAPIClient;

/**
 * Abstract base-class for all API requests.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public abstract class APIRequest 
{
    /**
     * The API command to perform.
     *
     * @author tobias.gierke@code-sourcery.de
     */
	public static enum Command 
	{
	    /**
	     * 
	     */
	    @JsonProperty("query")
		QUERY("query");
		
	    public final String text;
	    
		private Command(String text) {
			this.text = text;
		}
		
		public static Command fromString(String s)
		{
			for ( Command v : values() ) {
				if ( v.text.equals(s) ) {
					return v;
				}
			}
			throw new IllegalArgumentException("Unknown command '"+s+"'");
		}		
	}
	
	public String clientVersion;
	public final Command command;
	
	public APIRequest(APIRequest.Command cmd) {
		this.command = cmd;
	}
	
	public final void serialize(BinarySerializer serializer) throws IOException {
	    serializer.writeString( clientVersion );
	    serializer.writeString( command.text );
	    doSerialize( serializer );
	}
	
	protected abstract void doSerialize(BinarySerializer serializer) throws IOException; 
	
	public static APIRequest deserialize(BinarySerializer serializer) throws IOException 
	{
	    String version = serializer.readString();
        if ( ! IAPIClient.CLIENT_VERSION.equals( version ) ) {
            throw new IOException("Unknown client version: '"+version+"'");
        }
        
	    Command cmd = APIRequest.Command.fromString( serializer.readString() );
	    switch(cmd) 
	    {
            case QUERY:
                return QueryRequest.doDeserialize(serializer);
            default:
                throw new IOException("Unsupported command '"+cmd+"'");
	    }
	}
}