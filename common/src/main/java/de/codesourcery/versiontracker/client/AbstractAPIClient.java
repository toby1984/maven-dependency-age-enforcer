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
import de.codesourcery.versiontracker.common.Blacklist;
import de.codesourcery.versiontracker.common.QueryRequest;

import java.util.List;

public abstract class AbstractAPIClient implements IAPIClient
{
    protected boolean debugMode;
    
    protected final QueryRequest toQueryRequest(List<Artifact> artifacts,Blacklist blacklist) 
    {
        final QueryRequest request = new QueryRequest();
        request.clientVersion = CLIENT_VERSION;
        request.artifacts = artifacts;
        request.blacklist = blacklist;
        return request;
    }
    
    @Override
    public final void setDebugMode(boolean yes)
    {
        this.debugMode = yes;
    }
}