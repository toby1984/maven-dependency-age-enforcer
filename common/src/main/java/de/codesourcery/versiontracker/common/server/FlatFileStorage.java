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
import de.codesourcery.versiontracker.client.api.IAPIClient.Protocol;
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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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

	private static final int MAGIC_LENGTH_IN_BYTES = 8;
	private static final int RECORD_TAG_LENGTH_IN_BYTES = 1;

	// minimum size of a valid (empty) binary file
	// used when trying to check whether a file could possibly be a valid file
	private static final int MINIMUM_BINARY_FILESIZE = MAGIC_LENGTH_IN_BYTES + RECORD_TAG_LENGTH_IN_BYTES;

	// magic: file format v1
	private static final long MAGIC_V1 = 0xffffffffdeadbeefL;

	// magic: file format v2
	private static final long MAGIC_V2 = 0xffffffffdeadfaceL;

	private static final long[] VALID_MAGICS = {MAGIC_V1, MAGIC_V2};

	private static final ObjectMapper mapper = JSONHelper.newObjectMapper();

	public enum TaggedRecordType {
		VERSION_DATA( 0x01 ),
		END_OF_FILE( 0xff );

		final byte tag; // don't forget to adjust RECORD_TAG_LENGTH_IN_BYTES if this data type is ever changed

		TaggedRecordType(int tag) {
			this.tag = (byte) tag;
		}
	}

	private final SerializationFormat serializationFormatToWrite;
	// serialization format we've detected the last time we've
	// read the data file
	public SerializationFormat lastFileReadSerializationVersion;
	private final Protocol protocol;
	private final File file;

	// @GuardedBy( storageStatistics )
	private final StorageStatistics storageStatistics = new StorageStatistics();

	@Override
	public String toString() {
		return "FlatFileStorage[ " + file.getAbsolutePath() + " ]";
	}

	public FlatFileStorage(File file, Protocol protocol) {
		this( file, protocol, SerializationFormat.latest() );
	}

	public FlatFileStorage(File file, Protocol protocol, SerializationFormat serializationFormatToWrite) {
		this.file = file;
		this.protocol = protocol;
		this.serializationFormatToWrite = serializationFormatToWrite;
	}

	@Override
	public StorageStatistics getStatistics() {
		synchronized ( storageStatistics ) {
			return storageStatistics.createCopy();
		}
	}

	@Override
	public void resetStatistics()
	{
		synchronized( storageStatistics ) {
			storageStatistics.reset();
		}
	}

	@Override
	public synchronized List<VersionInfo> getAllVersions() throws IOException  
	{
		if ( ! file.exists() ) {
			return new ArrayList<>(0);
		}

        if ( LOG.isDebugEnabled() )
        {
            LOG.debug( "getAllVersions(): Loading data from {} file: {}", protocol, file.getAbsolutePath() );
        }
		final boolean assignMissingFirstSeenDate;
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
						lastFileReadSerializationVersion = SerializationFormat.fromVersionNumber( serializer.readShort() );
                        if ( LOG.isDebugEnabled() )
                        {
                            LOG.debug( "getAllVersions(): File {} uses {}", file.getAbsolutePath(), lastFileReadSerializationVersion );
                        }
                        byte tag;
						result = new ArrayList<>();
						while ( ( tag = serializer.readByte()) != TaggedRecordType.END_OF_FILE.tag )  {
							final int recordLength = serializer.readInt();
							if ( tag == TaggedRecordType.VERSION_DATA.tag )
							{
								final byte[] payload = new byte[recordLength];
								serializer.readBytes( payload );
								final BinarySerializer tmp = new BinarySerializer( BinarySerializer.IBuffer.wrap( payload ) );
								while ( ! tmp.isEOF() ) {
									result.add( VersionInfo.deserialize( tmp, lastFileReadSerializationVersion ) );
								}
							} else if ( recordLength > 0 ){
								// skip unknown records
								LOG.warn( "getAllVersions(): Skipping unknown record with type 0x"+Integer.toHexString( tag & 0xff ));
								serializer.buffer.skip( recordLength );
							}
						}
					} else {
						throw new IOException("Invalid file magic "+Long.toHexString( magic ) );
					}
				}
			}
			// field 'firstSeenByServer' got added with SerializationFormatVersion.V3.
			// populate with current date when reading such a database
			assignMissingFirstSeenDate = lastFileReadSerializationVersion.isBefore( SerializationFormat.V3 );
		} else if ( protocol == Protocol.JSON ) {
			result = mapper.readValue(file,new TypeReference<>() {});
			// field 'firstSeenByServer' got added with SerializationFormatVersion.V3.
			// populate with current date when reading such a database
			assignMissingFirstSeenDate = true;
		} else {
			throw new RuntimeException( "Unhandled protocol " + protocol );
		}

		ZonedDateTime mostRecentRequested = null;
		ZonedDateTime mostRecentFailure = null;
		ZonedDateTime mostRecentSuccess = null;
		int totalVersionCount = 0;

		boolean dataMigrated = false;
		final ZonedDateTime now = ZonedDateTime.now();
		for ( final VersionInfo versionInfo : result ) {

			if ( assignMissingFirstSeenDate ) {
                for ( Version version : versionInfo.versions )
                {
                    if ( version.firstSeenByServer == null )
                    {
                        version.firstSeenByServer = version.hasReleaseDate() ? version.releaseDate : now;
                        dataMigrated = true;
                    }
                }
            }
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

		if ( dataMigrated ) {
			LOG.debug("getAllVersions(): Migrated "+result.size()+" database entries from "+lastFileReadSerializationVersion+" => "+ serializationFormatToWrite );
			writeToDisk( result );
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
		final String in = "/home/tobi/tmp/versiontracker/artifacts.json.binary";
		final String out = in + ".txt";
		dumpToFile( new File( in ), new File( out ), guessFileType( new File( in ) ).orElseThrow() );
	}

	public static void dumpToFile(File inputFile,File outputFile, Protocol  protocol) throws IOException
	{
		final String text = dumpToString(inputFile, protocol);
		try ( FileWriter writer = new FileWriter( outputFile) ) {
			writer.write( text );
		}
	}

	public static String dumpToString(File file, Protocol protocol) throws IOException
	{
		final Function<ZonedDateTime,String> func = time -> time == null ? "n/a" : format.format(time);
		final StringBuilder buffer = new StringBuilder();

		final FlatFileStorage storage = new FlatFileStorage(file, protocol);
		for ( VersionInfo i : storage.getAllVersions() ) 
		{
			buffer.append("-----------------------------------").append("\n");
			buffer.append( "group id: " ).append( i.artifact.groupId ).append("\n");
			buffer.append( "artifact id: " ).append( i.artifact.artifactId ).append("\n");

			if  (i.latestReleaseVersion != null ) {
				buffer.append( "latest release: " ).append( i.latestReleaseVersion.versionString ).append( " (" )
                    .append( printDate( i.latestReleaseVersion.releaseDate ) ).append( ")" ).append("\n");
			} else {
				buffer.append("latest release : n/a").append("\n");
			}

			if  (i.latestSnapshotVersion != null ) {
				buffer.append( "latest snapshot : " ).append( i.latestSnapshotVersion.versionString ).append( " (" )
                    .append( printDate( i.latestSnapshotVersion.releaseDate ) ).append( ")" ).append("\n");
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
					buffer.append( "            " ).append( v.versionString ).append( " (" ).append( func.apply( v.releaseDate ) ).append( ")" ).append("\n");
				}
			}

			buffer.append( "lastRequestDate: " ).append( printDate( i.lastRequestDate ) ).append("\n");
			buffer.append( "creationDate: " ).append( printDate( i.creationDate ) ).append("\n");
			buffer.append( "lastSuccessDate: " ).append( printDate( i.lastSuccessDate ) ).append("\n");
			buffer.append( "lastFailureDate: " ).append( printDate( i.lastFailureDate ) ).append("\n");
			buffer.append( "lastRepositoryUpdate: " ).append( printDate( i.lastRepositoryUpdate ) ).append("\n");
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
						serializer.writeShort( serializationFormatToWrite.version );

						// write
						if ( ! allItems.isEmpty() ) {
							serializer.writeByte( TaggedRecordType.VERSION_DATA.tag );
							final ByteArrayOutputStream tmpOut = new ByteArrayOutputStream();
							try ( BinarySerializer serializer2 = new BinarySerializer( BinarySerializer.IBuffer.wrap( tmpOut ) ) ) {
								for ( VersionInfo info : allItems ) {
									info.serialize( serializer2, serializationFormatToWrite );
								}
							}
							final byte[] payload = tmpOut.toByteArray();
							serializer.writeInt( payload.length );
							serializer.writeBytes( payload );
						}
						serializer.writeByte( TaggedRecordType.END_OF_FILE.tag );
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
		//noinspection ResultOfMethodCallIgnored
		output.delete();
		
		try ( FlatFileStorage in = new FlatFileStorage(input,inputProtocol) ) {
			try ( FlatFileStorage out = new FlatFileStorage(output,outputProtocol) ) {
				out.saveOrUpdate( in.getAllVersions() );
			}
		}
	}

	public static Optional<Protocol> guessFileType(File file) throws IOException
	{
		if ( ! file.exists() ) {
			throw new FileNotFoundException( "File does not exist: " + file.getAbsolutePath() );
		}
		if ( ! file.canRead() ) {
			throw new IOException("Cannot read file: " + file.getAbsolutePath());
		}

		// let's see if it is JSON first as valid JSON files may be smaller than MINIMUM_BINARY_FILESIZE
		try ( InputStreamReader in = new InputStreamReader( new FileInputStream( file ) ) ) {
			while( true)
			{
				final int character = in.read();
				if ( character == -1 || ! Character.isWhitespace( character ) )
				{
					if ( character == '[' ) {
						return Optional.of( Protocol.JSON );
					}
					break;
				}
			}
		}

		if ( file.length() < MINIMUM_BINARY_FILESIZE ) { // file is too small to be a binary
			return Optional.empty();
		}

		try ( BinarySerializer ser = new BinarySerializer( BinarySerializer.IBuffer.wrap(new FileInputStream( file ) ) ) )
		{
			final long value = ser.readLong();
			if ( Arrays.stream(VALID_MAGICS).anyMatch( validMagic -> value == validMagic ) )
			{
				return Optional.of( Protocol.BINARY );
			}
		}
		return Optional.empty();
	}
}