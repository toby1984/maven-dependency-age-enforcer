package de.codesourcery.versiontracker.common.server;

import de.codesourcery.versiontracker.common.VersionInfo;

import java.io.Closeable;
import java.util.Optional;

public interface IBackgroundUpdater extends AutoCloseable, Closeable {

    void startThread();

    boolean requiresUpdate(Optional<VersionInfo> info);
}
