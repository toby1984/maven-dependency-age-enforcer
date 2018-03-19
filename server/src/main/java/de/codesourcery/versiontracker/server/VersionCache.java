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
package de.codesourcery.versiontracker.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.commons.lang3.Validate;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactMap;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.VersionInfo;

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
     * Adds a set of version information to the cache.
     * 
     * @param infos
     */
    public void addAll(Collection<VersionInfo> infos) 
    {
        Validate.notNull(infos,"infos must not be NULL");
        for ( VersionInfo i : infos ) {
            trackedVersions.put(i.artifact.groupId,i.artifact.artifactId,i);
        }
    }

    /**
     * Flushes the cache.
     */
    public void clear() {
        synchronized(VERSIONS_LOCK) 
        {
            trackedVersions.clear();
        }
    }

    /**
     * Searches this cache for a set of artifacts and invokes a callback
     * for each of them, indicating whether they were found in the cache.
     * 
     * @param toSearch
     * @param callback
     */
    public void visitMatches(List<Artifact> toSearch,BiConsumer<Artifact,Boolean> callback) 
    {
        synchronized(VERSIONS_LOCK) 
        {
            for ( Artifact a : toSearch ) 
            {
                final VersionInfo match = trackedVersions.get( a.groupId , a.artifactId );
                callback.accept(a , match == null ? Boolean.FALSE : Boolean.TRUE );                        
            }
        }
    }

    /**
     * Traverses all cache entries and returns <b>copies</b> of all entries that match a given predicate.
     * 
     * @param predicate
     * @return
     */
    public List<VersionInfo> getCopies(Predicate<VersionInfo> predicate) 
    {
        final List<VersionInfo> outdated = new ArrayList<>();
        synchronized(VERSIONS_LOCK) 
        {
            trackedVersions.visitValues( info -> {
                if ( predicate.test( info ) ) {
                    outdated.add( info.copy() );
                }
            });
        }        
        return outdated;
    }

    /**
     * Searches the cache for a given artifact and invokes a callback with the results.
     * 
     * @param artifact
     * @param callback
     * @return
     */
    public <T> T doWithVersion(Artifact artifact,Function<Optional<VersionInfo>,T> callback) 
    {
        synchronized( VERSIONS_LOCK ) 
        {
            final VersionInfo result = trackedVersions.get( artifact.groupId, artifact.artifactId );
            return callback.apply( Optional.ofNullable( result ) );
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
