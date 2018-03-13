package de.codesourcery.versiontracker;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface IVersionStorage
{
    public List<VersionInfo> getAllVersions() throws IOException;
    
    public void storeVersionInfo(VersionInfo info) throws IOException;
    
    public Optional<VersionInfo> loadVersionInfo(Artifact artifact) throws IOException;
    
    public void saveAll(List<VersionInfo> data) throws IOException;    
}
