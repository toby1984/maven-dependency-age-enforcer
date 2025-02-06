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

import de.codesourcery.versiontracker.common.server.SerializationFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
    // TODO: Performance - look-ups by version number string are O(n) - maybe use a Map or Set instead ? Not sure how often those are actually needed,
    //                     especially with the blacklisting feature that may need a linear scan of the collection anyway
    public List<Version> versions = new ArrayList<>();
    
    public VersionInfo() {
    }
    
    public void serialize(BinarySerializer serializer, SerializationFormat format) throws IOException
    {
    	artifact.serialize( serializer );
    	serializer.writeZonedDateTime( lastRequestDate );
    	serializer.writeZonedDateTime( creationDate );
    	serializer.writeZonedDateTime( lastSuccessDate );
    	serializer.writeZonedDateTime( lastFailureDate);
    	serializer.writeZonedDateTime( lastRepositoryUpdate );
    	if ( latestReleaseVersion != null ) {
    		serializer.writeBoolean( true);
    		latestReleaseVersion.serialize( serializer, format );
    	} else {
    		serializer.writeBoolean( false );
    	}
    	
    	if ( latestSnapshotVersion != null ) {
    		serializer.writeBoolean( true);
    		latestSnapshotVersion.serialize( serializer, format );
    		
    	} else {
    		serializer.writeBoolean( false );
    	}
    	serializer.writeInt( versions.size() );

    	for ( Version v : versions ) {
            v.serialize( serializer, format);
        }
    }

    public static VersionInfo deserialize(BinarySerializer serializer, SerializationFormat fileFormatVersion) throws IOException {

    	final VersionInfo  result = new VersionInfo();
    	result.artifact = Artifact.deserialize( serializer );
    	result.lastRequestDate = serializer.readZonedDateTime();
    	result.creationDate = serializer.readZonedDateTime();
    	result.lastSuccessDate = serializer.readZonedDateTime();
    	result.lastFailureDate = serializer.readZonedDateTime();
    	result.lastRepositoryUpdate = serializer.readZonedDateTime();
    	if ( serializer.readBoolean() ) {
    		result.latestReleaseVersion = Version.deserialize( serializer, fileFormatVersion);
    	}
    	if ( serializer.readBoolean() ) {
    		result.latestSnapshotVersion = Version.deserialize( serializer, fileFormatVersion);
    	}
    	final int size = serializer.readInt();
    	result.versions = new ArrayList<>(size);
    	for ( int i = 0 ; i < size ; i++) {
            result.versions.add( Version.deserialize( serializer, fileFormatVersion) );
        }
    	return result;
    }
    
    public boolean hasVersions() {
        return ! versions.isEmpty();
    }
    
    public Optional<Version> findLatestSnapshotVersion(Blacklist blacklist) {
    	return findLatestVersion(Artifact::isSnapshotVersion, blacklist);
    }
    
    public Optional<Version> findLatestReleaseVersion(Blacklist blacklist) {
    	return findLatestVersion(Artifact::isReleaseVersion, blacklist);
    }

    public boolean removeVersionsIf(Predicate<Version> p) {
        return versions.removeIf( p );
    }

    private Predicate<Version> blacklistPredicate(Blacklist blacklist) {
        return version -> blacklist != null && blacklist.isVersionBlacklisted( artifact.groupId, artifact.artifactId, version.versionString );
    }

    public List<Version> getVersionsSortedDescendingByReleaseDate(Predicate<String> versionPredicate, Blacklist blacklist)
    {
        final Predicate<Version> isBlacklisted = blacklistPredicate(blacklist);
        final List<Version> result = new ArrayList<>();
        for ( final Version v : versions )
        {
            if ( versionPredicate.test(v.versionString) && ! isBlacklisted.test( v ) ) {
                result.add(v);
            }
        }
        result.sort( Comparator.comparing( (Version a) -> a.firstSeenByServer ).reversed() );
        return result;
    }
    
    private Optional<Version> findLatestVersion(Predicate<String> versionPredicate, Blacklist blacklist) {
        final List<Version> list = getVersionsSortedDescendingByReleaseDate( versionPredicate, blacklist );
        if ( list.isEmpty() ) {
            return Optional.empty();
        }
        return Optional.of( list.get(0) );
    }
    
    public void addVersion(Version v )
    {
        // TODO: O(n) performance
        for ( Version existing : versions ) 
        {
            if ( existing.versionString.equals( v.versionString ) ) {
                if ( v.hasReleaseDate() && ! existing.hasReleaseDate() ) {
                    existing.releaseDate = v.releaseDate;
                }
                return;
            }
        }
        this.versions.add( v );
    }
    
    public VersionInfo(VersionInfo other)
    {
        //noinspection IncompleteCopyConstructor
        this.artifact = other.artifact == null ? null : other.artifact.copy();
       this.creationDate = other.creationDate;
       this.lastSuccessDate = other.lastSuccessDate;
       this.lastFailureDate = other.lastFailureDate;
        //noinspection IncompleteCopyConstructor
       this.versions = other.versions.stream().map( Version::copy ).collect( Collectors.toCollection( ArrayList::new ) );
        //noinspection IncompleteCopyConstructor
       this.latestReleaseVersion = other.latestReleaseVersion == null ? null : other.latestReleaseVersion.copy();
        //noinspection IncompleteCopyConstructor
       this.latestSnapshotVersion = other.latestSnapshotVersion == null ? null : other.latestSnapshotVersion.copy();
       this.lastRepositoryUpdate  = other.lastRepositoryUpdate;
       this.lastRequestDate = other.lastRequestDate;
    }

    public Optional<Boolean> hasReleaseDate(Version versionNumber) {
        return hasReleaseDate( versionNumber.versionString );
    }

    public Optional<Boolean> hasReleaseDate(String versionNumber) {
        return getVersion(versionNumber).map( Version::hasReleaseDate );
    }

    public Optional<Version> getVersion(String versionNumber)
    {
        // TODO: O(n) performance
        for ( Version v : versions ) {
            if ( v.versionString.equals( versionNumber ) ) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
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