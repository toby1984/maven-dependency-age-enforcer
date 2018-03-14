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

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.VersionInfo;

public class VersionTrackerTest
{
    private MockStorage storage;
    private VersionTracker tracker;
    
    private static final class MockStorage implements IVersionStorage
    {
        public final List<VersionInfo> stored = new ArrayList<>();
        
        @Override
        public synchronized List<VersionInfo> getAllVersions() throws IOException
        {
            return stored.stream().map( x -> x.copy() ).collect( Collectors.toCollection( ArrayList::new ) );
        }

        @Override
        public synchronized void storeVersionInfo(VersionInfo info) throws IOException
        {
            if ( ! stored.contains( info ) ) {
                stored.add( info.copy() );
            }
        }

        @Override
        public synchronized Optional<VersionInfo> loadVersionInfo(Artifact artifact) throws IOException
        {
            return stored.stream().filter( x -> x.artifact.matchesExcludingVersion( artifact ) ).findFirst();
        }

        @Override
        public synchronized void saveAll(List<VersionInfo> data) throws IOException
        {
            this.stored.clear();
            this.stored.addAll( data.stream().map(x->x.copy()).collect(Collectors.toList() ) );
        }
    }
    
    @Before
    public void setup() {
        this.storage = new MockStorage();
    }
    
    @After
    public void tearDown() {
        if ( tracker != null ) 
        {
            try {
                tracker.close();
            } finally {
                tracker = null;
            }
        }
    }
    
    @Test
    public void test() throws InterruptedException {

        final IVersionProvider provider = new IVersionProvider() {

            @Override
            public UpdateResult update(VersionInfo info) throws IOException
            {
                info.lastFailureDate = ZonedDateTime.now();
                return UpdateResult.BLACKLISTED;
            }
        };
        
        tracker = new VersionTracker(storage,provider);
        tracker.start();
        
        final Artifact artifact = new Artifact();
        artifact.groupId="de.codesourcery";
        artifact.artifactId="versiontracker";
        artifact.version="1.0";
        for ( int i = 0 ; i < 2 ; i++ ) 
        {
            Map<Artifact, VersionInfo> result = tracker.getVersionInfo( Collections.singletonList( artifact ) );
            Thread.sleep(1000);
            System.out.println( result );
        }
    }
}
