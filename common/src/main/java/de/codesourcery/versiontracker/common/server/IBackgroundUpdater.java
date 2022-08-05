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
