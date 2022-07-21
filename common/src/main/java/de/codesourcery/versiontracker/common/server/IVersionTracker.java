package de.codesourcery.versiontracker.common.server;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.VersionInfo;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public interface IVersionTracker extends AutoCloseable, Closeable  {

    Map<Artifact, VersionInfo> getVersionInfo(List<Artifact> artifacts, Predicate<Optional<VersionInfo>> isOutdated) throws InterruptedException;

    IVersionStorage getStorage();

    IVersionProvider getVersionProvider();

    int getMaxConcurrentThreads();

    void setMaxConcurrentThreads(int threadCount);
}
