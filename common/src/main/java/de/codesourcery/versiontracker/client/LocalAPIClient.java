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

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.Blacklist;
import de.codesourcery.versiontracker.common.QueryResponse;
import de.codesourcery.versiontracker.common.server.APIImpl;
import de.codesourcery.versiontracker.common.server.APIImpl.Mode;

import java.io.IOException;
import java.util.List;

public class LocalAPIClient extends AbstractAPIClient
{
    private final APIImpl impl = new APIImpl(Mode.CLIENT);
    
    public LocalAPIClient() {
    }
    
    @Override
    public List<ArtifactResponse> query(List<Artifact> artifacts, Blacklist blacklist) throws IOException
    {
        impl.init(debugMode,false);
        
        try 
        {
            final QueryResponse result = impl.processQuery( toQueryRequest(artifacts, blacklist) );
            return result.artifacts;
        } 
        catch (InterruptedException e) 
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception
    {
        impl.close();
    }
}
