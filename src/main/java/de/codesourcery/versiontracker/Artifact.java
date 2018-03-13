package de.codesourcery.versiontracker;

import java.util.Objects;

public class Artifact
{
    public String groupId;
    public String version;
    public String artifactId;
    public String classifier;
    public String type;
    
    public Artifact() {
    }
    
    public Artifact(Artifact artifact)
    {
        this.groupId = artifact.groupId;
        this.version = artifact.version;
        this.artifactId = artifact.artifactId;
        this.classifier = artifact.classifier;
        this.type = artifact.type;
    }

    public boolean hasSnapshotVersion() {
        return version != null && version.contains("-SNAPSHOT");
    }
    
    public boolean hasReleaseVersion() {
        return ! hasSnapshotVersion();
    }
    
    public Artifact copy() { 
        return new Artifact(this);
    }
    
    public boolean matchesExcludingVersion(Artifact other) 
    {
        return 
                Objects.equals( this.groupId ,other.groupId ) &&
                Objects.equals( this.artifactId ,other.artifactId ) &&
                Objects.equals( this.classifier ,other.classifier ) &&
                Objects.equals( this.type ,other.type );
    }

    public static int hashCode(Artifact a)
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((a.artifactId == null) ? 0 : a.artifactId.hashCode());
        result = prime * result + ((a.classifier == null) ? 0 : a.classifier.hashCode());
        result = prime * result + ((a.groupId == null) ? 0 : a.groupId.hashCode());
        result = prime * result + ((a.type == null) ? 0 : a.type.hashCode());
        return result;
    }
}
