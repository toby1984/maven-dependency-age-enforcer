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
package de.codesourcery.versiontracker.common;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.List;
import java.util.Optional;

/**
 * Responsible for handling persistence of artifact metadata.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public interface IVersionStorage
{
    /**
     * Retrieves metadata for all artifacts.
     * 
     * @return
     * @throws IOException
     */
    public List<VersionInfo> getAllVersions() throws IOException;
    
    /**
     * Stores or updates existing metadata.
     * 
     * @param info
     * @throws IOException
     * @see #saveAll(List)
     */
    public void storeVersionInfo(VersionInfo info) throws IOException;
    
    /**
     * Tries to load metadata for a given artifact.
     * 
     * @param artifact
     * @return
     * @throws IOException
     */
    public Optional<VersionInfo> loadVersionInfo(Artifact artifact) throws IOException;
    
    /**
     * Bulk-storage of new or updated <code>VersionInfo</code> instances.
     * 
     * @param data
     * @throws IOException
     * 
     * @see #storeVersionInfo(VersionInfo)
     */
    public void saveAll(List<VersionInfo> data) throws IOException;    
}
