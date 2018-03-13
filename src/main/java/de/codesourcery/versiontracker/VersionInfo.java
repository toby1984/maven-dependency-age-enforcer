package de.codesourcery.versiontracker;

import java.time.ZonedDateTime;

public class VersionInfo
{
    public Artifact artifact;
    public ZonedDateTime creationDate;
    public ZonedDateTime lastSuccessDate;
    public ZonedDateTime lastFailureDate;
    public String latestReleaseVersion;
    public String latestSnapshotVersion;
    public ZonedDateTime lastRepositoryUpdate;
    
    public VersionInfo() {
    }
    
    public VersionInfo(VersionInfo versionInfo)
    {
       this.artifact = versionInfo.artifact == null ? null : versionInfo.artifact.copy();
       this.creationDate = versionInfo.creationDate;
       this.lastSuccessDate = versionInfo.lastSuccessDate;
       this.lastFailureDate = versionInfo.lastFailureDate;
       this.latestReleaseVersion = versionInfo.latestReleaseVersion;
       this.latestSnapshotVersion = versionInfo.latestSnapshotVersion;
       this.lastRepositoryUpdate  = versionInfo.lastRepositoryUpdate;
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
}