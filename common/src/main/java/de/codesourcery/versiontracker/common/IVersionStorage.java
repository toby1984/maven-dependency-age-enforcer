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
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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
     * Returns all artifact metadata that either has never been requested at all
     * <b>or</b> , last successful request happened more than <code>lastSuccessDuration</code>
     * time ago or last failed request happened more than <code>lastFailureDuration</code>
     * ago (whatever happened last takes precedence here).
     * 
     * @param lastSuccessDuration
     * @param lastFailureDuration
     * @return
     * @throws IOException
     */
    public default List<VersionInfo> getAllStaleVersions(Duration lastSuccessDuration, Duration lastFailureDuration,ZonedDateTime now) throws IOException 
    {
        final List<VersionInfo> result = new ArrayList<>();
        for ( VersionInfo info : getAllVersions() )
        {
            if ( isStaleVersion(info,lastSuccessDuration,lastFailureDuration,now) ) {
                result.add( info );
            }
        }
        return result;
    }    
    
    public static boolean isStaleVersion(VersionInfo info,Duration lastSuccessDuration, Duration lastFailureDuration,ZonedDateTime now) 
    {
        boolean add = false;
        if ( info.lastPolledDate() == null ) { // both dates are NULL
            add = true;
        } 
        else if ( info.lastSuccessDate != null &&  info.lastFailureDate != null ) // both are not NULL
        {
            if ( info.lastSuccessDate.isAfter( info.lastFailureDate ) ) {
                add = Duration.between( info.lastSuccessDate,now ).compareTo( lastSuccessDuration ) > 0;
            } else {
                add = Duration.between( info.lastFailureDate,now ).compareTo( lastFailureDuration ) > 0;
            }
        } else if ( info.lastSuccessDate == null ) {
            add = Duration.between( info.lastFailureDate,now ).compareTo( lastFailureDuration ) > 0;
        } else {
            add = Duration.between( info.lastSuccessDate,now ).compareTo( lastSuccessDuration ) > 0; 
        } 
        return add;
    }
    
    /**
     * Stores or updates existing metadata.
     * 
     * @param info
     * @throws IOException
     * @see #saveOrUpdate(List)
     */
    public void saveOrUpdate(VersionInfo info) throws IOException;
    
    /**
     * Tries to load metadata for a given artifact.
     * 
     * @param artifact
     * @return
     * @throws IOException
     */
    public default Optional<VersionInfo> getVersionInfo(Artifact artifact) throws IOException
    {
        return getAllVersions().stream().filter( item -> item.artifact.matchesExcludingVersion( artifact ) ).findFirst();
    }    
    
    public default void updateLastRequestDate(Artifact artifact, ZonedDateTime date) throws IOException 
    {
        final Optional<VersionInfo> existing = getVersionInfo(artifact);
        if ( existing.isPresent() ) {
            existing.get().lastRequestDate = date;
            saveOrUpdate( existing.get() );
        }
    }
    
    /**
     * Bulk-storage of new or updated <code>VersionInfo</code> instances.
     * 
     * @param data
     * @throws IOException
     * 
     * @see #saveOrUpdate(VersionInfo)
     */
    public void saveOrUpdate(List<VersionInfo> data) throws IOException;    
}
