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

import com.fasterxml.jackson.annotation.JsonProperty;
import de.codesourcery.versiontracker.client.api.IAPIClient;
import de.codesourcery.versiontracker.common.server.SerializationFormat;
import org.apache.commons.lang3.Validate;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

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
	public enum Command
	{
	    /**
	     * Query artifact information.
	     */
	    @JsonProperty("query")
		QUERY("query");
		
	    public final String text;
	    
		Command(String text) {
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

	public ClientVersion clientVersion;
	public final Command command;

	public APIRequest(APIRequest.Command cmd) {
		Validate.notNull( cmd, "cmd must not be null" );
		this.command = cmd;
	}

	public APIRequest(APIRequest.Command cmd, ClientVersion clientVersion) {
		this(cmd);
		this.clientVersion = clientVersion;
	}
	
	public final void serialize(BinarySerializer serializer) throws IOException {
	    serializer.writeString( clientVersion.versionString );
	    serializer.writeString( command.text );
	    doSerialize( serializer, clientVersion.serializationFormat );
	}
	
	protected abstract void doSerialize(BinarySerializer serializer, SerializationFormat format) throws IOException;
	
	public static APIRequest deserialize(BinarySerializer serializer) throws IOException 
	{
	    final String version = serializer.readString();
		final ClientVersion actualVersion = ClientVersion.fromVersionNumber( version );

	    final Command cmd = APIRequest.Command.fromString( serializer.readString() );
		return switch ( cmd ) {
			case QUERY -> QueryRequest.doDeserialize( serializer, actualVersion );
		};
	}
}