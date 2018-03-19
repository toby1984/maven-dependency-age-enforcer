/**
 * Copyright 2015 Tobias Gierke <tobias.gierke@code-sourcery.de>
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
package de.codesourcery.versiontracker.server;

import java.util.concurrent.locks.ReentrantLock;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactMap;

/**
 * Shared component that holds a map of {@link ReentrantLock}s for 
 * each {@link Artifact} the application knows about and makes
 * sure that artifact metadata only gets updated by either
 * an incoming API request <b>or</b> by the background updater
 * thread but not both at the same time.
 *
 * This class is thread-safe.
 * 
 * @author tobias.gierke@code-sourcery.de
 */
public class SharedLockCache 
{
    // @GuaredBy( locks )
    private static final ArtifactMap<ReentrantLock> locks = new ArtifactMap<>();
    
    public static interface ThrowingRunnable 
    {
        public void run() throws Exception,InterruptedException;
    }
    
    public SharedLockCache() {
    }
    
    /**
     * Invokes a callback while holding the lock for a given artifact.
     * 
     * @param artifact
     * @param callback
     * @throws Exception
     * @throws InterruptedException
     */
    public void doWhileLocked(Artifact artifact,ThrowingRunnable callback) throws Exception,InterruptedException 
    {
        ReentrantLock lock;
        synchronized( locks ) 
        {
            lock = locks.get( artifact.groupId , artifact.artifactId );
            if ( lock == null ) 
            {
                lock = new ReentrantLock(true);
                locks.put( artifact.groupId, artifact.artifactId, lock );
            }
        }
        
        lock.lockInterruptibly();
        try {
            callback.run();
        } finally {
            lock.unlock();
        }
    }
}
