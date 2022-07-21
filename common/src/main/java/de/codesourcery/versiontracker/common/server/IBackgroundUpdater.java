package de.codesourcery.versiontracker.common.server;

import de.codesourcery.versiontracker.common.RequestsPerHour;
import de.codesourcery.versiontracker.common.VersionInfo;

import java.io.Closeable;
import java.util.Optional;

public interface IBackgroundUpdater extends AutoCloseable, Closeable {

    final class Statistics {
        public final RequestsPerHour scheduledUpdates;


        public Statistics() {
            scheduledUpdates = new RequestsPerHour();
        }

        public Statistics(Statistics other) {
            this.scheduledUpdates = other.scheduledUpdates.createCopy();
        }

        public Statistics createCopy() {
            return new Statistics(this);
        }
    }

    Statistics getStatistics();

    void startThread();

    boolean requiresUpdate(Optional<VersionInfo> info);
}
