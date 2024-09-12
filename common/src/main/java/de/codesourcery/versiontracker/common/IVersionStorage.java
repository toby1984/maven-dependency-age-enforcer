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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Responsible for handling persistence of artifact metadata.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public interface IVersionStorage extends AutoCloseable
{
    Logger STORAGE_LOG = LogManager.getLogger(IVersionStorage.class);

    final class StorageStatistics
    {
        public ZonedDateTime lastStatisticsReset = ZonedDateTime.now();

        public int totalArtifactCount;
        public int totalVersionCount;

        public long storageSizeInBytes;

        public RequestsPerHour reads = new RequestsPerHour();
        public RequestsPerHour writes = new RequestsPerHour();

        /** Most recent time a user requested meta-data for an artifact */
        public ZonedDateTime mostRecentRequested = null;
        /** Most recent time meta-data for an artifact could not be retrieved from the repository */
        public ZonedDateTime mostRecentFailure = null;
        /** Most recent time meta-data for an artifact was retrieved successfully from the repository */
        public ZonedDateTime mostRecentSuccess = null;

        public StorageStatistics() {
        }

        public void reset()
        {
            totalArtifactCount = 0;
            totalVersionCount = 0;
            storageSizeInBytes = 0;

            reads.reset();
            writes.reset();

            mostRecentRequested = null;
            mostRecentFailure = null;
            mostRecentSuccess = null;

            lastStatisticsReset = ZonedDateTime.now();
        }

        public Optional<ZonedDateTime> mostRecentRequested() {
            return Optional.ofNullable( mostRecentRequested );
        }

        public Optional<ZonedDateTime> mostRecentFailure() {
            return Optional.ofNullable( mostRecentFailure );
        }

        public Optional<ZonedDateTime> mostRecentSuccess() {
            return Optional.ofNullable( mostRecentSuccess );
        }

        public StorageStatistics(StorageStatistics other) {
            this.totalArtifactCount = other.totalArtifactCount;
            this.totalVersionCount = other.totalVersionCount;
            this.storageSizeInBytes = other.storageSizeInBytes;
            this.mostRecentRequested = other.mostRecentRequested;
            this.mostRecentFailure = other.mostRecentFailure;
            this.mostRecentSuccess = other.mostRecentSuccess;
            this.reads = other.reads.createCopy();
            this.writes = other.writes.createCopy();
            this.lastStatisticsReset = other.lastStatisticsReset;
        }

        public StorageStatistics createCopy() {
            return new StorageStatistics(this);
        }
    }

    /**
     * Retrieves metadata for all artifacts.
     * 
     * @return
     * @throws IOException
     */
    List<VersionInfo> getAllVersions() throws IOException;

    /**
     * Retrieves metadata for all artifacts.
     *
     * @return
     * @throws IOException
     */
    default List<VersionInfo> getAllVersions(String groupRegEx, String artifactRegEx) throws IOException {

        final Pattern groupPattern = Pattern.compile( groupRegEx );
        final Pattern artifactPattern = Pattern.compile( artifactRegEx );
        return getAllVersions().stream().filter( v -> groupPattern.matcher( v.artifact.groupId ).matches() &&
                artifactPattern.matcher( v.artifact.artifactId ).matches() )
            .toList();
    }

    /**
     * Returns statistics about this storage.
     *
     * @return
     * @see #resetStatistics()
     */
    StorageStatistics getStatistics();

    /**
     * Reset internal statistics.
     * @see #getStatistics()
     */
    void resetStatistics();

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
    default List<VersionInfo> getAllStaleVersions(Duration lastSuccessDuration, Duration lastFailureDuration,ZonedDateTime now) throws IOException
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
    
    static boolean isStaleVersion(VersionInfo info,Duration minUpdateDelayAfterSuccess, Duration minUpdateDelayAfterFailure,ZonedDateTime now)
    {
        boolean isStale;
        if ( info.lastPolledDate() == null ) { // lastSuccessDate AND  lastFailureDate are NULL
            isStale = true;
            if ( STORAGE_LOG.isDebugEnabled() ) {
                STORAGE_LOG.debug("isStaleVersion(): [stale,never successfully polled at all] "+info.artifact);
            }
        } 
        else if ( info.lastSuccessDate != null &&  info.lastFailureDate != null ) // both are not NULL
        {
            if ( info.lastSuccessDate.isAfter( info.lastFailureDate ) ) {
                isStale = Duration.between( info.lastSuccessDate,now ).compareTo( minUpdateDelayAfterSuccess ) > 0;
                if ( isStale && STORAGE_LOG.isDebugEnabled() )
                {
                    STORAGE_LOG.debug("isStaleVersion(): [stale,lastSuccessDate "+info.lastSuccessDate+" is more than "+minUpdateDelayAfterSuccess+" ago] "+info.artifact);
                }                
            } else {
                isStale = Duration.between( info.lastFailureDate,now ).compareTo( minUpdateDelayAfterFailure ) > 0;
                if ( isStale && STORAGE_LOG.isDebugEnabled() )
                {
                    STORAGE_LOG.debug("isStaleVersion(): [stale,lastFailureDate "+info.lastFailureDate+" is more than "+minUpdateDelayAfterFailure+" ago] "+info.artifact);
                }                  
            }

        } else if ( info.lastSuccessDate == null ) { // polling never succeeded yet
            isStale = Duration.between( info.lastFailureDate,now ).compareTo( minUpdateDelayAfterFailure ) > 0;
            if ( isStale && STORAGE_LOG.isDebugEnabled() )
            {
                STORAGE_LOG.debug("isStaleVersion(): [stale,lastFailureDate "+info.lastFailureDate+" is more than "+minUpdateDelayAfterFailure+" ago] "+info.artifact);
            }             
        } else {
            // polling has always succeeded
            isStale = Duration.between( info.lastSuccessDate,now ).compareTo( minUpdateDelayAfterSuccess ) > 0;
            if ( isStale && STORAGE_LOG.isDebugEnabled() )
            {
                STORAGE_LOG.debug("isStaleVersion(): [stale,lastSuccessDate "+info.lastSuccessDate+" is more than "+minUpdateDelayAfterSuccess+" ago] "+info.artifact);
            }             
        } 
        return isStale;
    }
    
    /**
     * Stores or updates existing metadata.
     * 
     * @param info
     * @throws IOException
     * @see #saveOrUpdate(List)
     */
    void saveOrUpdate(VersionInfo info) throws IOException;
    
    /**
     * Tries to load metadata for a given artifact.
     * 
     * @param artifact
     * @return
     * @throws IOException
     */
    default Optional<VersionInfo> getVersionInfo(Artifact artifact) throws IOException
    {
        return getAllVersions().stream().filter( item -> item.artifact.matchesExcludingVersion( artifact ) ).findFirst();
    }    
    
    default void updateLastRequestDate(Artifact artifact, ZonedDateTime date) throws IOException
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
    void saveOrUpdate(List<VersionInfo> data) throws IOException;
}
