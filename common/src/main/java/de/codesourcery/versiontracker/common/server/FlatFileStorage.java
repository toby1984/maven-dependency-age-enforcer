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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private static final DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("UTC"));
    
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
    
    private static String toKey(VersionInfo x) 
    {
        return x.artifact.groupId+":"+x.artifact.artifactId;
    }

    @Override
    public synchronized void saveOrUpdate(List<VersionInfo> data) throws IOException 
    {
        assertNoDuplicates(data);
        
        final Set<String> set = data.stream().map( FlatFileStorage::toKey ).collect( Collectors.toSet() );
        final List<VersionInfo> mergeTarget = getAllVersions();
        mergeTarget.removeIf( x -> set.contains( toKey(x) ) );
        mergeTarget.addAll( data );
        JSONHelper.newObjectMapper().writeValue(file,mergeTarget);
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
    
    public static void main(String[] args) throws IOException {
        
        dumpToFile( new File("/tmp/artifactTest.noCache"),new File("/tmp/artifactTest.noCache.txt") );
        dumpToFile( new File("/tmp/artifactTest.withCache"),new File("/tmp/artifactTest.withCache.txt") );
    }
    
    public static void dumpToFile(File inputFile,File outputFile) throws IOException 
    {
        String text = dumpToString(inputFile);
        try ( FileWriter writer = new FileWriter( outputFile) ) {
            writer.write( text );
        }
    }
    
    public static String dumpToString(File file) throws IOException 
    {
        final Function<ZonedDateTime,String> func = time -> {
            return time == null ? "n/a" : format.format(time);
        };
        final StringBuilder buffer = new StringBuilder();
        
        final FlatFileStorage storage = new FlatFileStorage(file);
        for ( VersionInfo i : storage.getAllVersions() ) 
        {
            buffer.append("-----------------------------------").append("\n");
            buffer.append("group id: "+i.artifact.groupId).append("\n");
            buffer.append("artifact id: "+i.artifact.artifactId).append("\n");

            if  (i.latestReleaseVersion != null ) {
                buffer.append("latest release: "+i.latestReleaseVersion.versionString+" ("+printDate( i.latestReleaseVersion.releaseDate )+")" ).append("\n");
            } else {
                buffer.append("latest release : n/a").append("\n");
            }

            if  (i.latestSnapshotVersion != null ) {
                buffer.append("latest snapshot : "+i.latestSnapshotVersion.versionString+" ("+printDate( i.latestSnapshotVersion.releaseDate )+")" ).append("\n");
            } else {
                buffer.append("latest snapshot : n/a").append("\n");
            }
            if ( i.versions == null || i.versions.isEmpty() ) {
                buffer.append("versions: n/a").append("\n");
            } else {
                buffer.append("versions:").append("\n");
                final List<Version> list = new ArrayList<>( i.versions );
                list.sort( (a,b) -> a.versionString.compareTo( b.versionString ) );
                for ( Version v : list ) 
                {
                    buffer.append("            "+v.versionString+" ("+func.apply( v.releaseDate )+")").append("\n");
                }
            }

            buffer.append("lastRequestDate: "+printDate(i.lastRequestDate)).append("\n");
            buffer.append("creationDate: "+printDate(i.creationDate)).append("\n");
            buffer.append("lastSuccessDate: "+printDate(i.lastSuccessDate)).append("\n");
            buffer.append("lastFailureDate: "+printDate(i.lastFailureDate)).append("\n");
            buffer.append("lastRepositoryUpdate: "+printDate(i.lastRepositoryUpdate)).append("\n");       
        }
        return buffer.toString();
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

    @Override
    public void close() throws Exception
    {
    }
}