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

/**
 * Used to check whether an artifact version should be ignored and not considered when checking for possible updates. 
 *
 * @author tobias.gierke@code-sourcery.de
 */
public interface IBlacklistCheck
{
    /**
     * Returns whether <b>any</b> version of an artifact is blacklist.
     * 
     * @param artifact
     * @return
     */    
    boolean isArtifactBlacklisted(Artifact artifact);
    
    /**
     * Check whether a given artifact version is blacklisted.
     * @param groupId
     * @param artifactId
     * @param version
     * @return 
     */    
    boolean isVersionBlacklisted(String groupId,String artifactId,String version);
}
