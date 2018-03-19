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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.commons.lang3.Validate;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactMap;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.VersionInfo;

/**
 * A decorator that wraps a {@link IVersionStorage} and 
 * caches <b>all</b> the results in-memory.
 * 
 * Using this decorator obviously does not work well if you have a <b>lot</b> of artifacts
 * that need to be handled...
 * 
 * This class is thread-safe.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class CachingStorageDecorator implements IVersionStorage 
{
    private final IVersionStorage delegate;

    // @GuardedBy( cache )
    private boolean initialized;

    // @GuardedBy( cache )
    private final ArtifactMap<VersionInfo> cache = new ArtifactMap<>();

    public CachingStorageDecorator(IVersionStorage delegate) {
        Validate.notNull(delegate,"delegate must not be NULL");
        this.delegate = delegate;
    }

    private static final class CollectingVisitor implements Consumer<VersionInfo> {

        public final List<VersionInfo> list = new ArrayList<>();

        @Override
        public void accept(VersionInfo t) {
            list.add( t.copy() );
        }
    }

    private void maybeInit() throws IOException 
    {
        synchronized(cache) 
        {
            if ( ! initialized ) {
                cache.clear();
                delegate.getAllVersions().forEach( v -> 
                {
                    cache.put( v.artifact.groupId, v.artifact.artifactId, v );
                });
                initialized = true;
            }
        }
    }

    @Override
    public List<VersionInfo> getAllVersions() throws IOException 
    {
        synchronized(cache) 
        {
            maybeInit();            
            final CollectingVisitor collector = new CollectingVisitor();
            cache.visitValues( collector);
            return collector.list;               
        }
    }

    @Override
    public Optional<VersionInfo> getVersionInfo(Artifact artifact) throws IOException 
    {
        maybeInit();
        synchronized(cache) 
        {
            final VersionInfo result = cache.get( artifact.groupId, artifact.artifactId );
            return result == null ? Optional.empty() : Optional.of(result.copy());
        }        
    }

    @Override
    public void saveOrUpdate(VersionInfo info) throws IOException {
        maybeInit();
        delegate.saveOrUpdate( info );
        synchronized(cache) 
        {
            cache.put( info.artifact.groupId,info.artifact.artifactId,info.copy());
        }
    }    

    @Override
    public void saveOrUpdate(List<VersionInfo> data) throws IOException 
    {
        maybeInit();        
        delegate.saveOrUpdate( data );
        synchronized(cache) 
        {
            for ( VersionInfo i : data ) {
                cache.put( i.artifact.groupId,i.artifact.artifactId,i.copy());
            }
        }
    }
}