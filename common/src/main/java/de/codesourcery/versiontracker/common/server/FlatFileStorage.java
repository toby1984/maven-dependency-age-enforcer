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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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

	// magic: file format v1
	private static final long MAGIC_V1 = 0xdeadbeef;

	// magic: file format v2
	private static final long MAGIC_V2 = 0xdeadface;

	private static final SerializationFormat CURRENT_FILE_FORMAT = SerializationFormat.V2;

	private static final ObjectMapper mapper = JSONHelper.newObjectMapper();

	private final Protocol protocol;
	private File file;

	// @GuardedBy( storageStatistics )
	private final StorageStatistics storageStatistics = new StorageStatistics();

	public FlatFileStorage(File file) 
	{
		this(file,Protocol.JSON);
	}

	@Override
	public String toString() {
		return "FlatFileStorage[ " + file.getAbsolutePath() + " ]";
	}

	public FlatFileStorage(File file, Protocol protocol) {
		this.file = file;
		this.protocol = protocol;
	}

	@Override
	public StorageStatistics getStatistics() {
		synchronized ( storageStatistics ) {
			return storageStatistics.createCopy();
		}
	}

	@Override
	public synchronized List<VersionInfo> getAllVersions() throws IOException  
	{
		if ( ! file.exists() ) {
			return new ArrayList<>(0);
		}

		final List<VersionInfo>  result;
		if ( protocol == Protocol.BINARY )
		{
			try ( BufferedInputStream in = new BufferedInputStream( new FileInputStream(file) ) )
			{
				try ( final BinarySerializer serializer = new BinarySerializer(BinarySerializer.IBuffer.wrap(in ) ) ) 
				{
					long magic = serializer.readLong();
					if ( magic == MAGIC_V1 ) {
						final int count = serializer.readInt();
						result = new ArrayList<>(count);
						for ( int i = 0 ; i < count ; i++ ) {
							result.add( VersionInfo.deserialize( serializer, SerializationFormat.V1 ) );
						}
					} else if ( magic == MAGIC_V2 ) {
						final short version = serializer.readShort();
						if ( version != CURRENT_FILE_FORMAT.version ) {
							throw new IOException( "Unsupported file format version: " + version+", expected "+ CURRENT_FILE_FORMAT );
						}
						byte tag;
						result = new ArrayList<>();
						while ( ( tag = serializer.readByte()) != TaggedRecord.RecordType.END_OF_FILE.tag )  {
							final int recordLength = serializer.readInt();
							if ( tag == TaggedRecord.RecordType.VERSION_DATA.tag )
							{
								final byte[] payload = new byte[recordLength];
								serializer.readBytes( payload );
								final BinarySerializer tmp = new BinarySerializer( BinarySerializer.IBuffer.wrap( payload ) );
								while ( ! tmp.isEOF() ) {
									result.add( VersionInfo.deserialize( tmp, SerializationFormat.V2 ) );
								}
							} else if ( recordLength > 0 ){
								// skip record
								serializer.buffer.skip( recordLength );
							}
						}
					} else {
						throw new IOException("Invalid file magic "+Long.toHexString( magic ) );
					}
				}
			}
		} else if ( protocol == Protocol.JSON ) {
			result = mapper.readValue(file,new TypeReference<>() {});
		} else {
			throw new RuntimeException( "Unhandled protocol " + protocol );
		}

		ZonedDateTime mostRecentRequested = null;
		ZonedDateTime mostRecentFailure = null;
		ZonedDateTime mostRecentSuccess = null;
		int totalVersionCount = 0;
		for ( final VersionInfo versionInfo : result ) {
			totalVersionCount += versionInfo.versions.size();
			if ( versionInfo.lastRequestDate != null ) {
				mostRecentRequested = max( versionInfo.lastRequestDate, mostRecentRequested );
			}
			if ( versionInfo.lastFailureDate != null ) {
				mostRecentFailure = max( versionInfo.lastFailureDate, mostRecentFailure );
			}
			if ( versionInfo.lastSuccessDate != null ) {
				mostRecentSuccess = max( versionInfo.lastSuccessDate, mostRecentSuccess );
			}
		}

		// update statistics
		synchronized ( storageStatistics ) {
			storageStatistics.storageSizeInBytes = file.length();
			storageStatistics.reads.update( result.size() );
			storageStatistics.totalArtifactCount = result.size();
			storageStatistics.totalVersionCount = totalVersionCount;
			storageStatistics.mostRecentSuccess = mostRecentSuccess;
			storageStatistics.mostRecentFailure = mostRecentFailure;
			storageStatistics.mostRecentRequested = mostRecentRequested;
		}

		return result;
	}

	private static ZonedDateTime max(ZonedDateTime d1, ZonedDateTime d2) {
		if ( d1 != null && d2 != null ) {
			return d1.compareTo(d2) > 0 ? d1 : d2;
		}
		return d1 == null ? d2 : d1;
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
				list.sort( Comparator.comparing( a -> a.versionString ) );
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
        
		List<VersionInfo> toUpdate = getAllVersions();
		toUpdate.removeIf( item -> item.artifact.matchesExcludingVersion( info.artifact) );
		toUpdate.add( info );

		writeToDisk( toUpdate );
	}

	@Override
	public synchronized void saveOrUpdate(List<VersionInfo> data) throws IOException 
	{
		final Set<String> set = data.stream().map( FlatFileStorage::toKey ).collect( Collectors.toSet() );
		final List<VersionInfo> toUpdate = getAllVersions();
		toUpdate.removeIf( x -> set.contains( toKey(x) ) );
		toUpdate.addAll( data );

		writeToDisk( toUpdate );
	}

	private void writeToDisk(List<VersionInfo> allItems) throws IOException
	{
		// TODO: Add support for only rewriting changed items and not all of them
		if ( LOG.isDebugEnabled() ) {
		    LOG.debug("saveOrUpdate(): Persisting "+ allItems.size()+" entries...");
		}
		long start = System.nanoTime();
		try {
			final File tmpFile = new File( file.getAbsolutePath() + ".tmp" );
			if ( tmpFile.exists() ) {
				Files.delete( tmpFile.toPath() );
			}
			if ( protocol == Protocol.BINARY ) {
				try ( BufferedOutputStream out = new BufferedOutputStream( new FileOutputStream( tmpFile ) ) ) {
					try ( final BinarySerializer serializer = new BinarySerializer( BinarySerializer.IBuffer.wrap( out ) ) )
					{
						serializer.writeLong( MAGIC_V2 );
						serializer.writeShort( CURRENT_FILE_FORMAT.version );

						// write
						if ( ! allItems.isEmpty() ) {
							serializer.writeByte( TaggedRecord.RecordType.VERSION_DATA.tag );
							final ByteArrayOutputStream tmpOut = new ByteArrayOutputStream();
							try ( BinarySerializer serializer2 = new BinarySerializer( BinarySerializer.IBuffer.wrap( tmpOut ) ) ) {
								for ( VersionInfo info : allItems ) {
									info.serialize( serializer2, CURRENT_FILE_FORMAT );
								}
							}
							final byte[] payload = tmpOut.toByteArray();
							serializer.writeInt( payload.length );
							serializer.writeBytes( payload );
						}
						serializer.writeByte( TaggedRecord.RecordType.END_OF_FILE.tag );
					}
				}
			}
			else if ( protocol == Protocol.JSON ) {
				mapper.writeValue( tmpFile, allItems );
			}
			else {
				throw new RuntimeException( "Unhandled protocol: " + protocol );
			}

			Files.move( tmpFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING );

			// update statistics
			synchronized ( storageStatistics ) {
				storageStatistics.storageSizeInBytes = file.length();
				storageStatistics.totalArtifactCount = allItems.size();
				storageStatistics.totalVersionCount = allItems.stream().mapToInt( x -> x.versions.size() ).sum();
				storageStatistics.writes.update( allItems.size() );
			}
		}
		finally {
			if ( LOG.isDebugEnabled() ) {
				long end = System.nanoTime();
				long elapsedMillis = (end-start)/1_000_000;
				LOG.debug("saveOrUpdate(): Persisted "+ allItems.size()+" entries in "+elapsedMillis+" ms");
			}
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