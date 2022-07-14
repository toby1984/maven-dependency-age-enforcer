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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codesourcery.versiontracker.client.IAPIClient.Protocol;
import de.codesourcery.versiontracker.common.BinarySerializer;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.JSONHelper;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

/**
 * Simple {@link IVersionStorage} implementation that just stores everything as JSON
 * inside a file.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class FlatFileStorage implements IVersionStorage
{
    private static final Logger LOG = LogManager.getLogger(FlatFileStorage.class);
    
	private static final DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("UTC"));

	private static final long MAGIC = 0xdeadbeef;

	private static final ObjectMapper mapper = JSONHelper.newObjectMapper();

	private final Protocol protocol;
	private File file;

	public FlatFileStorage(File file) 
	{
		this(file,Protocol.JSON);
	}    

	public FlatFileStorage(File file,Protocol protocol) {
		this.file = file;
		this.protocol = protocol;
	}

	@Override
	public synchronized List<VersionInfo> getAllVersions() throws IOException  
	{
		if ( ! file.exists() ) {
			return new ArrayList<>(0);
		}
		if ( protocol == Protocol.BINARY ) 
		{
			final List<VersionInfo>  result;
			try ( BufferedInputStream in = new BufferedInputStream( new FileInputStream(file) ) ) 
			{
				try ( final BinarySerializer serializer = new BinarySerializer(BinarySerializer.IBuffer.wrap(in ) ) ) 
				{
					long magic = serializer.readLong();
					if ( magic != MAGIC ) {
						throw new IOException("Invalid file magic "+Long.toHexString( magic ) );
					}
					final int count = serializer.readInt();
					result = new ArrayList<>(count);
					for ( int i = 0 ; i < count ; i++ ) {
						result.add( VersionInfo.deserialize( serializer ) );
					}
				}
			}
			return result;
		} 
		if ( protocol == Protocol.JSON ) {
			return mapper.readValue(file,new TypeReference<>() {});
		} 
		throw new RuntimeException("Unhandled protocol "+protocol);
	}

	private static String toKey(VersionInfo x) 
	{
		return x.artifact.groupId+":"+x.artifact.artifactId;
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
		final Function<ZonedDateTime,String> func = time -> time == null ? "n/a" : format.format(time);
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
        if ( LOG.isDebugEnabled() ) {
            LOG.debug("saveOrUpdate(): Called for "+info);
        }
        
		List<VersionInfo> all = getAllVersions();
		all.removeIf( item -> item.artifact.matchesExcludingVersion( info.artifact) );
		all.add( info );
		saveOrUpdate( all );
	}

	@Override
	public synchronized void saveOrUpdate(List<VersionInfo> data) throws IOException 
	{
		final Set<String> set = data.stream().map( FlatFileStorage::toKey ).collect( Collectors.toSet() );
		final List<VersionInfo> mergeTarget = getAllVersions();
		mergeTarget.removeIf( x -> set.contains( toKey(x) ) );
		mergeTarget.addAll( data );
		
		if ( LOG.isDebugEnabled() ) {
		    LOG.debug("saveOrUpdate(): Persisting "+mergeTarget.size()+" entries");
		}

		if ( protocol == Protocol.BINARY ) {
			try ( BufferedOutputStream out = new BufferedOutputStream( new FileOutputStream(file) ) ) 
			{
				try ( final BinarySerializer serializer = new BinarySerializer( BinarySerializer.IBuffer.wrap( out ) ) ) {
					serializer.writeLong( MAGIC );
					serializer.writeInt( mergeTarget.size() );
					for ( VersionInfo info : mergeTarget ) {
						info.serialize( serializer );
					}
				}
			}
		} else if ( protocol == Protocol.JSON ) {
			mapper.writeValue(file,mergeTarget);
		} else {
			throw new RuntimeException("Unhandled protocol: "+protocol);
		}
	}    

	@Override
	public void close() throws Exception
	{
	    LOG.info("close(): File storage for "+file+" got closed");
	}
	
	public static void convert(File input,Protocol inputProtocol,File output,Protocol outputProtocol) throws Exception {
		if ( input.equals( output ) ) {
			throw new IllegalArgumentException("Input and output file must differ");
		}
		output.delete();
		
		try ( FlatFileStorage in = new FlatFileStorage(input,inputProtocol) ) {
			try ( FlatFileStorage out = new FlatFileStorage(output,outputProtocol) ) {
				out.saveOrUpdate( in.getAllVersions() );
			}
		}
	}
}