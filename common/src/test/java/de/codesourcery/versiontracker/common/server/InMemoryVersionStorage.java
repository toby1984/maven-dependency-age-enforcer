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

import java.util.ArrayList;
import java.util.List;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.VersionInfo;

public class InMemoryVersionStorage implements IVersionStorage
{
    private final List<VersionInfo> versions = new ArrayList<>();

    private final StorageStatistics statistics =
        new StorageStatistics();

    @Override
    public List<VersionInfo> getAllVersions()
    {
        synchronized( versions )
        {
            final List<VersionInfo> result = new ArrayList<>( this.versions.size() );
            this.versions.forEach( v -> result.add( v.copy() ) );
            return result;
        }
    }

    @Override
    public StorageStatistics getStatistics()
    {
        synchronized ( this.statistics )
        {
            return this.statistics.createCopy();
        }
    }

    @Override
    public void resetStatistics()
    {
        synchronized ( this.statistics )
        {
            this.statistics.reset();
        }
    }

    @Override
    public void saveOrUpdate(VersionInfo info)
    {
        synchronized( versions ) {
            for ( int i = 0 ; i < this.versions.size() ; i++ ) {
                final VersionInfo info2 = this.versions.get(i);
                if ( info2.artifact.matchesExcludingVersion( info.artifact ) ) {
                    this.versions.set(i, info.copy() );
                    return;
                }
            }
            this.versions.add( info.copy() );
        }
    }

    @Override
    public void saveOrUpdate(List<VersionInfo> data)
    {
        synchronized( versions ) {
            data.forEach( this::saveOrUpdate );
        }
    }

    @Override
    public void close()
    {
    }
}