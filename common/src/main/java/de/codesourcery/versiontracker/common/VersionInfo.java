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

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Artifact metadata.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class VersionInfo
{
	private static final Logger LOG = LogManager.getLogger(VersionInfo.class);

	/**
	 * The artifact this metadata is for.
	 */
    public Artifact artifact;
    
    /**
     * Last time a user request information about this artifact.
     */
    public ZonedDateTime lastRequestDate;
    
    /**
     * Date when this metadata instance got created.
     */    
    public ZonedDateTime creationDate;
    
    /**
     * Date when metadata for this artifact was last fetched successfully.
     */
    public ZonedDateTime lastSuccessDate;
    
    /**
     * Date when fetching metadata for this artifact failed last.
     */    
    public ZonedDateTime lastFailureDate;
    
    /**
     * Last repository update as contained in the maven-metadata.xml file.
     */
    public ZonedDateTime lastRepositoryUpdate;
    
    /**
     * Latest release version as contained in the maven-metadata.xml file.
     */
    public Version latestReleaseVersion;
    
    /**
     * Latest snapshot version as contained in the maven-metadata.xml file.
     */    
    public Version latestSnapshotVersion;
    
    /**
     * The versions (maybe with their upload dates) as contained in the maven-metadata.xml file.
     */
    public List<Version> versions = new ArrayList<>();
    
    public VersionInfo() {
    }
    
    public boolean hasVersions() {
        return ! versions.isEmpty();
    }
    
    public Optional<Version> findLatestSnapshotVersion(Blacklist blacklist) {
    	return findLatestVersion(Artifact::isSnapshotVersion,Artifact.VERSION_COMPARATOR,blacklist);
    }
    
    public Optional<Version> findLatestReleaseVersion(Blacklist blacklist) {
    	return findLatestVersion(Artifact::isReleaseVersion,Artifact.VERSION_COMPARATOR,blacklist);
    }
    
    private Optional<Version> findLatestVersion(Predicate<String> versionPredicate,Comparator<String> versionComparator,Blacklist blacklist) {
    	
    	Optional<Version> latest = Optional.empty();
    	final Predicate<Version> isBlacklisted = v -> blacklist != null &&
    			blacklist.isVersionBlacklisted(artifact.groupId, artifact.artifactId, v.versionString );
    	
    	for ( Version v : versions ) 
    	{
    		if ( versionPredicate.test( v.versionString ) ) 
    		{ 
    			if ( ! isBlacklisted.test( v ) ) {
    				if ( ! latest.isPresent() || versionComparator.compare(v.versionString,latest.get().versionString) > 0 ) {
    					latest = Optional.ofNullable( v );
    				}
    			} else {
    				LOG.debug("findLatestVersion(): [BLACKLISTED] "+artifact.groupId+":"+artifact.artifactId+":"+v.versionString);
    			}
    		}
    	}
    	return latest;
    }
    
    public void maybeAddVersion( Version v ) 
    {
        for ( Version existing : versions ) 
        {
            if ( existing.versionString.equals( v.versionString ) ) {
                return;
            }
        }
        this.versions.add( v );
    }
    
    public boolean hasSameValues(VersionInfo other) 
    {
        return Objects.equals(this.artifact,other.artifact) &&
                Objects.equals( this.lastRequestDate, other.lastRequestDate ) &&
                Objects.equals( this.creationDate, other.creationDate ) &&
                Objects.equals( this.lastSuccessDate, other.lastSuccessDate ) &&
                Objects.equals( this.lastFailureDate, other.lastFailureDate ) &&
                Objects.equals( this.latestReleaseVersion, other.latestReleaseVersion ) &&
                Objects.equals( this.latestSnapshotVersion, other.latestSnapshotVersion ) &&
                Objects.equals( this.lastRepositoryUpdate, other.lastRepositoryUpdate ) &&
                Objects.equals( this.versions, other.versions );
    }
    
    public VersionInfo(VersionInfo other)
    {
       this.artifact = other.artifact == null ? null : other.artifact.copy();
       this.creationDate = other.creationDate;
       this.lastSuccessDate = other.lastSuccessDate;
       this.lastFailureDate = other.lastFailureDate;
       this.versions = other.versions.stream().map( x->x.copy()).collect( Collectors.toCollection( ArrayList::new ) );
       this.latestReleaseVersion = other.latestReleaseVersion == null ? null : other.latestReleaseVersion.copy();
       this.latestSnapshotVersion = other.latestSnapshotVersion == null ? null : other.latestSnapshotVersion.copy();
       this.lastRepositoryUpdate  = other.lastRepositoryUpdate;
       this.lastRequestDate = other.lastRequestDate;
    }
    
    public Optional<Version> getDetails(String versionNumber) 
    {
        for ( Version v : versions ) {
            if ( v.versionString.equals( versionNumber ) ) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }
    
    public boolean hasVersion(String versionNumber) {
        for ( Version v : versions ) {
            if ( v.versionString.equals( versionNumber ) ) {
                return true;
            }
        }
        return false;
    }
    public boolean hasVersionWithReleaseDate(String versionNumber) {
        for ( Version v : versions ) {
            if ( v.versionString.equals( versionNumber ) ) {
                return v.hasReleaseDate();
            }
        }
        return false;
    }
    
    public boolean isNewItem() {
        return lastSuccessDate == null && lastFailureDate == null;
    }

    public ZonedDateTime lastPolledDate() 
    {
        if ( lastSuccessDate != null && lastFailureDate == null ) {
            return lastSuccessDate;
        }
        if ( lastSuccessDate == null && lastFailureDate != null ) {
            return lastFailureDate;
        }
        if ( lastSuccessDate == null && lastFailureDate == null ) {
            return null;
        }
        return lastSuccessDate.compareTo( lastFailureDate ) > 0 ? lastSuccessDate : lastFailureDate;
    }
    
    public VersionInfo copy() {
        return new VersionInfo(this);
    }
    
    @Override
    public boolean equals(Object obj)
    {
        if ( obj instanceof VersionInfo) 
        {
            return this.artifact.matchesExcludingVersion( ((VersionInfo) obj).artifact ); 
        }
        return false;
    }
    
    @Override
    public int hashCode()
    {
        return artifact == null ? 0 : Artifact.hashCode( artifact );
    }

    @Override
    public String toString()
    {
        return "VersionInfo [artifact=" + artifact + ", creationDate=" + creationDate + ", lastSuccessDate="
                + lastSuccessDate + ", lastFailureDate=" + lastFailureDate + ", latestReleaseVersion="
                + latestReleaseVersion + ", latestSnapshotVersion=" + latestSnapshotVersion + ", lastRepositoryUpdate="
                + lastRepositoryUpdate + ", versions=" + versions + "]";
    }
}