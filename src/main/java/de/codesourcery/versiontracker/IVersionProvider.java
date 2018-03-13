package de.codesourcery.versiontracker;

import java.io.IOException;
import java.util.Optional;

public interface IVersionProvider
{
    public Optional<VersionInfo> getLatestVersion(Artifact artifact) throws IOException;
}
