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
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.codesourcery.versiontracker.common.server.SerializationFormat;

/**
 * API request that looks for updates for a given set of artifacts.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class QueryRequest extends APIRequest 
{
    public List<Artifact> artifacts = new ArrayList<>();
    public Blacklist blacklist;

	public QueryRequest() {
		this( null );
	}

	public QueryRequest(@JsonProperty("clientVersion") ClientVersion clientVersion)
	{
		super(Command.QUERY, clientVersion);
	}
	
	public boolean equals(Object obj) 
	{
		if ( obj instanceof QueryRequest o ) {
			if ( this.artifacts.size() != o.artifacts.size() ) {
				return false;
			}
			for ( Artifact a1 : this.artifacts ) {
				if ( o.artifacts.stream().noneMatch( x -> x.equals(a1) ) ) {
					return false;
				}
			}
			if ( this.blacklist == null || o.blacklist == null ) {
				return this.blacklist == o.blacklist;
			}
			return this.blacklist.equals( o.blacklist );
		}
		return false;
	}

    @Override
    protected void doSerialize(BinarySerializer serializer, SerializationFormat format) throws IOException
    {
        serializer.writeInt( artifacts.size() );
        for ( Artifact a : artifacts ) {
            a.serialize( serializer );
        }
		blacklist.serialize( serializer );
    }
    
    static QueryRequest doDeserialize(BinarySerializer serializer, ClientVersion version) throws IOException {
        final QueryRequest result = new QueryRequest(version);
        for ( int count = serializer.readInt() ; count > 0 ; count--) 
        {
            final Artifact artifact = Artifact.deserialize( serializer );
            result.artifacts.add( artifact );
        }
        result.blacklist = Blacklist.deserialize(serializer);
        return result;
    }
}