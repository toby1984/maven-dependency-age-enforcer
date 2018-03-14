/**
 * Copyright 2015 Tobias Gierke <tobias.gierke@code-sourcery.de>
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
import java.util.List;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.Blacklist;

/**
 * Client interface for communicating with the endpoint responsible for 
 * artifact metadata retrieval.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public interface IAPIClient 
{
    /**
     * Client protocol version.
     */
    public static final String CLIENT_VERSION = "1.0";
    
    /**
     * Query artifact metadata.
     * 
     * @param artifacts artifacts to query for
     * @param blacklist blacklist to use when figuring out which artifact version is the latest, may be <code>null</code>
     * @return
     * @throws IOException
     */
	public List<ArtifactResponse> query(List<Artifact> artifacts,Blacklist blacklist) throws IOException;
	
	/**
	 * Enable/disable debug output.
	 * 
	 * @param yes
	 */
	public void setDebugMode(boolean yes);
}