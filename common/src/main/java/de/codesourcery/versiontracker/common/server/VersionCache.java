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
package de.codesourcery.versiontracker.common.server;

import de.codesourcery.versiontracker.common.ArtifactMap;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.VersionInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory list of all {@link VersionInfo} instances
 * 
 * TODO: Add dirty tracking, move responsibility for interacting with the {@link IVersionProvider} here.
 * TODO: Stop holding everything in memory, does not scale... 
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class VersionCache 
{
    private final Object VERSIONS_LOCK = new Object();

    // @GuardedBy( VERSIONS_LOCK )
    private final ArtifactMap<VersionInfo> trackedVersions = new ArtifactMap<>();

    /**
     * Returns all entries in the cache.
     * 
     * @return
     * @deprecated Will be removed , does not scale.
     */
    @Deprecated
    public List<VersionInfo> getAllEntries() {
        final List<VersionInfo> result = new ArrayList<>( trackedVersions.size() );
        trackedVersions.visitValues( result::add );
        return result;
    }

    /**
     * Returns the total number of entries.
     * 
     * @return
     */
    public int size() {
        synchronized(VERSIONS_LOCK) 
        {
            return trackedVersions.size();
        }
    }

    /**
     * Atomically updates this cache, removing a set of given elements and
     * then adding another.
     * 
     * @param toRemove
     * @param toAdd
     */
    public void replaceAll(List<VersionInfo> toRemove,List<VersionInfo> toAdd) 
    {
        synchronized( VERSIONS_LOCK ) 
        {
            for ( VersionInfo info : toRemove ) 
            {
                trackedVersions.remove( info.artifact.groupId, info.artifact.artifactId );
            }
            for ( VersionInfo info : toAdd ) 
            {
                trackedVersions.put( info.artifact.groupId, info.artifact.artifactId, info );
            }
        }
    }
}
