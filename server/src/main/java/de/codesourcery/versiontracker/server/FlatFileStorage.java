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

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;

import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.JSONHelper;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;

/**
 * Simple {@link IVersionStorage} implementation that just stores everything as JSON
 * inside a file.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class FlatFileStorage implements IVersionStorage
{
    private File file;

    public FlatFileStorage(File file) {
        this.file = file;
    }    

    @Override
    public synchronized List<VersionInfo> getAllVersions() throws IOException  
    {
        List<VersionInfo>  result = new ArrayList<>();
        if ( ! file.exists() ) {
            return result;
        }

        result = JSONHelper.newObjectMapper().readValue(file,new TypeReference<List<VersionInfo>>() {});
        assertNoDuplicates(result); // TODO: Remove debug code
        return result;
    }

    @Override
    public synchronized void saveOrUpdate(List<VersionInfo> data) throws IOException 
    {
        assertNoDuplicates(data);
        JSONHelper.newObjectMapper().writeValue(file,data);
    }

    public static void assertNoDuplicates(List<VersionInfo> data) 
    {
        for ( int i1 = 0 ; i1 < data.size() ; i1++ ) 
        {
            final VersionInfo a = data.get(i1);
            for ( int i2 = 0 ; i2 < data.size() ; i2++ ) 
            { 
                if ( i2 != i1 ) {
                    final VersionInfo b = data.get(i2);
                    if ( a.artifact.matchesExcludingVersion( b.artifact ) ) {
                        throw new IllegalArgumentException("Duplicate entry at index ("+i1+","+i2+") for \n"+a+"\n\n<->\n\n"+b);
                    }
                }
            }
        }
    }

    private static final DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("UTC"));

    public static void main(String[] args) throws IOException {

        final Function<ZonedDateTime,String> func = time -> {
            return time == null ? "n/a" : format.format(time);
        };
        FlatFileStorage storage = new FlatFileStorage(new File("/tmp/artifacts.json"));
        for ( VersionInfo i : storage.getAllVersions() ) {
            System.out.println("-----------------------------------");
            System.out.println("group id: "+i.artifact.groupId);
            System.out.println("artifact id: "+i.artifact.artifactId);

            if  (i.latestReleaseVersion != null ) {
                System.out.println("latest release: "+i.latestReleaseVersion.versionString+" ("+printDate( i.latestReleaseVersion.releaseDate )+")" );
            } else {
                System.out.println("latest release : n/a");
            }

            if  (i.latestSnapshotVersion != null ) {
                System.out.println("latest snapshot : "+i.latestSnapshotVersion.versionString+" ("+printDate( i.latestSnapshotVersion.releaseDate )+")" );
            } else {
                System.out.println("latest snapshot : n/a");
            }
            if ( i.versions == null || i.versions.isEmpty() ) {
                System.out.println("versions: n/a");
            } else {
                System.out.println("versions:");
                final List<Version> list = new ArrayList<>( i.versions );
                list.sort( (a,b) -> a.versionString.compareTo( b.versionString ) );
                for ( Version v : list ) 
                {
                    System.out.println("            "+v.versionString+" ("+func.apply( v.releaseDate )+")");
                }
            }

            System.out.println("lastRequestDate: "+printDate(i.lastRequestDate));
            System.out.println("creationDate: "+printDate(i.creationDate));
            System.out.println("lastSuccessDate: "+printDate(i.lastSuccessDate));
            System.out.println("lastFailureDate: "+printDate(i.lastFailureDate));
            System.out.println("lastRepositoryUpdate: "+printDate(i.lastRepositoryUpdate));       
        }
    }

    private static String printDate(ZonedDateTime dt) {
        if ( dt == null ) {
            return "n/a";
        }
        return format.format( dt );
    }    

    @Override
    public synchronized void saveOrUpdate(VersionInfo info) throws IOException
    {
        List<VersionInfo> all = getAllVersions();
        all.removeIf( item -> item.artifact.matchesExcludingVersion( info.artifact) );
        all.add( info );
        saveOrUpdate( all );
    }
}