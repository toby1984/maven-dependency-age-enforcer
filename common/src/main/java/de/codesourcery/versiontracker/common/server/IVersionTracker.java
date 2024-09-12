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

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.VersionInfo;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public interface IVersionTracker extends AutoCloseable, Closeable  {

    Map<Artifact, VersionInfo> getVersionInfo(List<Artifact> artifacts, BiPredicate<VersionInfo,Artifact> isOutdated) throws InterruptedException;

    /**
     * Force fetching artifact information from Maven Central again.
     *
     * @param groupId
     * @param artifactId
     * @return version information, possibly but not necessarily updated
     * @throws InterruptedException
     */
    VersionInfo forceUpdate(String groupId, String artifactId) throws InterruptedException;

    IVersionStorage getStorage();

    IVersionProvider getVersionProvider();

    int getMaxConcurrentThreads();

    void setMaxConcurrentThreads(int threadCount);
}
