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

import de.codesourcery.versiontracker.common.APIRequest.Command;
import de.codesourcery.versiontracker.common.server.SerializationFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * API response in reply to {@link QueryRequest}.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class QueryResponse extends APIResponse
{
    public final List<ArtifactResponse> artifacts=new ArrayList<>();

	public QueryResponse() {
        super(Command.QUERY);
    }
	
	public boolean equals(Object obj) 
	{
		if ( obj instanceof final QueryResponse o ) {
			if ( this.artifacts.size() != o.artifacts.size() ) {
				System.out.println(">>>>>>>> Artifact size mismatch");
				return false;
			}
			for (ArtifactResponse a1 : artifacts) 
			{
				if ( o.artifacts.stream().noneMatch( x -> x.equals(a1 ) ) ) {
					System.out.println(">>>>>>>> Artifact not found: "+a1);
					return false;
				}
			}
			return true;
		}
		return false;
	}
	
    @Override
    protected void doSerialize(BinarySerializer serializer) throws IOException 
    {
        serializer.writeInt( artifacts.size() );
        for ( ArtifactResponse resp : artifacts ) {
            resp.serialize(serializer, SerializationFormat.V1 );
        }
    }

    public static APIResponse doDeserialize(BinarySerializer serializer) throws IOException {
        final QueryResponse result = new QueryResponse();
        for ( int count = serializer.readInt() ; count > 0 ; count-- ) 
        {
            result.artifacts.add( ArtifactResponse.deserialize( serializer, SerializationFormat.V1 ) );
        }
        return result;
    }
}
