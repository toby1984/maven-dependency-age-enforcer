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
import de.codesourcery.versiontracker.common.server.SerializationFormat;

/**
 * Abstract base-class for all API responses.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public abstract class APIResponse 
{
    /**
     * Server protocol version.
     */
    public static final ServerVersion SERVER_VERSION = ServerVersion.latest();
    
	public ServerVersion serverVersion;
	public APIRequest.Command command;
	
   public APIResponse(APIRequest.Command cmd) {
       this.command = cmd;
   }

   public APIResponse(APIRequest.Command cmd, ServerVersion serverVersion) {
       this(cmd);
       this.serverVersion = serverVersion;
    }
	
    public final void serialize(BinarySerializer serializer, SerializationFormat format) throws IOException {
        serializer.writeString( serverVersion.versionString );
        serializer.writeString( command.text );
        doSerialize(serializer, format);
    }	
    
    protected abstract void doSerialize(BinarySerializer serializer, SerializationFormat format) throws IOException;
    
    public static APIResponse deserialize(BinarySerializer serializer) throws IOException {
        final String serverVersion = serializer.readString();
        final ServerVersion version = ServerVersion.fromVersionNumber( serverVersion );

        final APIRequest.Command  cmd = APIRequest.Command.fromString( serializer.readString() );
        return switch ( cmd ) {
            case QUERY -> QueryResponse.doDeserialize( serializer, version.serializationFormat );
        };
    }
}
