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
    public static final String SERVER_VERSION = "1.0";
    
	public String serverVersion;
	public APIRequest.Command command;
	
   public APIResponse(APIRequest.Command cmd) {
        this.serverVersion = SERVER_VERSION;
        this.command = cmd;
    }
	
    public final void serialize(BinarySerializer serializer) throws IOException {
        serializer.writeString( serverVersion );
        serializer.writeString( command.text );
        doSerialize(serializer);
    }	
    
    protected abstract void doSerialize(BinarySerializer serializer) throws IOException;
    
    public static APIResponse deserialize(BinarySerializer serializer) throws IOException {
        final String serverVersion = serializer.readString();
        if ( ! SERVER_VERSION.equals( serverVersion ) ) {
            throw new IOException("Unknown server version: '"+serverVersion+"'");
        }
        final APIRequest.Command  cmd = APIRequest.Command.fromString( serializer.readString() );
        return switch ( cmd ) {
            case QUERY -> QueryResponse.doDeserialize( serializer );
        };
    }
}
