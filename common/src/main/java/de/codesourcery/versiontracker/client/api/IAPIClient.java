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
package de.codesourcery.versiontracker.client.api;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.Blacklist;

import java.io.IOException;
import java.util.List;

/**
 * Client interface for communicating with the endpoint responsible for 
 * artifact metadata retrieval.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public interface IAPIClient extends AutoCloseable
{
    /**
     * Client protocol version.
     */
    String CLIENT_VERSION = "1.0";
    
    enum Protocol {
        JSON((byte) 0xab),
        BINARY((byte) 0xba);
        
        public final byte id;

        Protocol(byte id) {
            this.id = id;
        }
        
        public static Protocol fromByte(byte id) {
            if ( id == JSON.id ) {
                return JSON;
            }
            if ( id == BINARY.id ) {
                return BINARY;
            }
            throw new IllegalArgumentException("Unsupporter protocol ID : 0x"+Integer.toHexString( id & 0xff ) );
        }
    }
    
    static byte[] toWireFormat(byte[] input,Protocol protocol)
    {
        final byte[] tmp = new byte[ input.length+1 ];
        tmp[0] = protocol.id;
        for ( int readPtr=0,writePtr=1,len=input.length ; readPtr < len ; readPtr++,writePtr++ ) {
            tmp[writePtr] = input[readPtr];
        }
        return tmp;
    }
    
    /**
     * Query artifact metadata.
     * 
     * @param artifacts artifacts to query for
     * @param blacklist blacklist to use when figuring out which artifact version is the latest, may be <code>null</code>
     * @return
     * @throws IOException
     */
	List<ArtifactResponse> query(List<Artifact> artifacts,Blacklist blacklist) throws IOException;
	
	/**
	 * Enable/disable debug output.
	 * 
	 * This setting overrides verbose mode.
	 * 
	 * @param yes
	 */
	void setDebugMode(boolean yes);
}