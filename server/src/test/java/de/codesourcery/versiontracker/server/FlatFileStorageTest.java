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
package de.codesourcery.versiontracker.server;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;
import de.codesourcery.versiontracker.common.server.FlatFileStorage;

public class FlatFileStorageTest
{
    private File file;
    
    @Before
    public void setup() throws IOException {
        file = File.createTempFile("versiontracktest",".json");
        file.delete();
    }
    
    @After
    public void tearDown() {
        if ( file != null ) 
        {
            try {
                file.delete();
                file.deleteOnExit();
            } finally {
                file = null;
            }
        }
    }    
    
    @Test
    public void testStoreAndLoadEmpty() throws IOException 
    {
        FlatFileStorage storage = new FlatFileStorage(file);
        List<VersionInfo> data = new ArrayList<>();
        storage.saveOrUpdate( data );
        
        storage = new FlatFileStorage(file);
        List<VersionInfo> loaded = storage.getAllVersions();
        Assert.assertEquals(0,loaded.size());
    }
    
    private VersionInfo createData() {
        VersionInfo info = new VersionInfo();
        info.artifact = new Artifact();
        info.artifact.groupId = "de.codesourcery";
        info.artifact.artifactId = "test";
        info.artifact.version = "1.0.0";
        info.artifact.setClassifier("jdk9");
        info.artifact.type= "jar";
        
        final ZonedDateTime now = ZonedDateTime.now();
        final Version a = new Version("1.2", now.plusDays( 1 ));
        final Version b = new Version("1.3", now.plusDays( 5 ));
        final Version c = new Version("1.4", now.plusDays( 6 ));

        info.lastRequestDate = now;
        info.creationDate = now.plus(Duration.ofSeconds(1));
        info.lastSuccessDate = now.plus(Duration.ofSeconds(2));
        info.lastFailureDate = now.plus(Duration.ofSeconds(13));
        info.latestReleaseVersion = a;
        info.latestSnapshotVersion = b;
        info.lastRepositoryUpdate = now.plus(Duration.ofSeconds(6));
        info.versions = new ArrayList<>( Arrays.asList(a,b,c) );
        return info;
    }
    
    public void testStoreAndLoadOne() throws IOException 
    {
        final VersionInfo info = createData();
        final VersionInfo copy = info.copy();
        
        FlatFileStorage storage = new FlatFileStorage(file);
        storage.saveOrUpdate(info);
        
        storage = new FlatFileStorage(file);
        final List<VersionInfo> loaded = storage.getAllVersions();
        Assert.assertEquals(1,loaded.size());
        Assert.assertEquals( copy , loaded.get(0) );
    } 
    
    @Test
    public void testStoreAndLoadBulk() throws IOException 
    {
        final VersionInfo info = createData();
        final VersionInfo copy = info.copy();
        
        FlatFileStorage storage = new FlatFileStorage(file);
        storage.saveOrUpdate( Collections.singletonList( info ));
        
        storage = new FlatFileStorage(file);
        final List<VersionInfo> loaded = storage.getAllVersions();
        Assert.assertEquals(1,loaded.size());
        Assert.assertEquals( copy , loaded.get(0) );
    }     
    
//    @Test
//    public void loadTest() throws IOException 
//    {
//        FlatFileStorage storage = new FlatFileStorage(new File("/tmp/artifacts.json"));
//        List<VersionInfo> versions = storage.getAllVersions();
//        
//        final Comparator<Artifact> cmp = new Comparator<Artifact>()
//        {
//            private int nullSafeCompare(String a,String b) {
//                if ( a != null && b != null ) {
//                    return a.compareTo( b );
//                } 
//                return ( a != null ) ? -1 : 1;
//            }
//            
//            @Override
//            public int compare(Artifact a, Artifact b)
//            {
//                int result=nullSafeCompare(a.groupId,b.groupId);
//                if ( result == 0 ) {
//                    result = nullSafeCompare(a.artifactId,  b.artifactId );
//                    if ( result == 0 ) {
//                        result = nullSafeCompare(a.version, b.version);
//                    }
//                }
//                return result;
//            }
//        };
//        versions.sort( (a,b) -> cmp.compare(a.artifact,b.artifact) );
//        for ( VersionInfo i : versions ) 
//        {
//          System.out.println("|"+i.artifact.groupId+"|"+i.artifact.artifactId+"|"+i.artifact);    
//        }
//    }
}
