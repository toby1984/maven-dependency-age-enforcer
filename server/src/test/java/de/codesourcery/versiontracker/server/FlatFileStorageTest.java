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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import de.codesourcery.versiontracker.client.api.IAPIClient;
import de.codesourcery.versiontracker.client.api.IAPIClient.Protocol;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;
import de.codesourcery.versiontracker.common.server.FlatFileStorage;
import de.codesourcery.versiontracker.common.server.SerializationFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FlatFileStorageTest
{
	private File file;

	@BeforeEach
	public void setup() throws IOException {
		file = File.createTempFile("versiontracktest",".json");
		file.delete();
	}

	@AfterEach
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
	public void testStoreAndLoadEmptyBinary() throws IOException
	{
		FlatFileStorage storage = new FlatFileStorage(file, Protocol.BINARY);
		Assertions.assertNull( storage.lastFileReadSerializationVersion );

		List<VersionInfo> data = new ArrayList<>();
		storage.saveOrUpdate( data );

		storage = new FlatFileStorage(file, Protocol.BINARY);
		Assertions.assertNull( storage.lastFileReadSerializationVersion );

		List<VersionInfo> loaded = storage.getAllVersions();
		assertEquals(0,loaded.size());
		assertEquals( SerializationFormat.latest(), storage.lastFileReadSerializationVersion );
	}

	@Test
	public void testStoreAndLoadEmptyJSON() throws IOException
	{
		FlatFileStorage storage = new FlatFileStorage(file, IAPIClient.Protocol.JSON);
		List<VersionInfo> data = new ArrayList<>();
		storage.saveOrUpdate( data );

		storage = new FlatFileStorage(file, IAPIClient.Protocol.JSON);
		List<VersionInfo> loaded = storage.getAllVersions();
		assertEquals(0,loaded.size());
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

	@Test
	public void testMigrationFromSerializationFormatV2toV3SetsFirstSeenDate() throws IOException
	{
		final VersionInfo info = createData();
		final VersionInfo copy = info.copy();

		FlatFileStorage storage = new FlatFileStorage(file, Protocol.BINARY, SerializationFormat.V2);
		storage.saveOrUpdate(info);

		storage = new FlatFileStorage(file, IAPIClient.Protocol.BINARY);
		final List<VersionInfo> loaded = storage.getAllVersions();
		assertEquals( SerializationFormat.V2, storage.lastFileReadSerializationVersion );

		for ( final VersionInfo v : loaded )
		{
			Assertions.assertTrue( v.versions.size() > 1 );
			Assertions.assertTrue( v.versions.stream().allMatch( x -> x.firstSeenByServer != null ) );
		}
		assertEquals(1,loaded.size());
		assertEquals( copy , loaded.get(0) );
	}

	@Test
	public void testStoreAndLoadOne() throws IOException 
	{
		final VersionInfo info = createData();
		final VersionInfo copy = info.copy();

		FlatFileStorage storage = new FlatFileStorage(file, IAPIClient.Protocol.JSON);
		storage.saveOrUpdate(info);

		storage = new FlatFileStorage(file, IAPIClient.Protocol.JSON);
		final List<VersionInfo> loaded = storage.getAllVersions();
		assertEquals(1,loaded.size());
		assertEquals( copy , loaded.get(0) );
	} 

	@Test
	public void testStoreAndLoadBulk() throws IOException 
	{
		final VersionInfo info = createData();
		final VersionInfo copy = info.copy();

		FlatFileStorage storage = new FlatFileStorage(file, IAPIClient.Protocol.JSON);
		storage.saveOrUpdate( Collections.singletonList( info ));

		storage = new FlatFileStorage(file, IAPIClient.Protocol.JSON);
		final List<VersionInfo> loaded = storage.getAllVersions();
		assertEquals(1,loaded.size());
		assertEquals( copy , loaded.get(0) );
	}     

	@Test
	public void testLoadTimeJSON() throws Exception {

		final File tmp = File.createTempFile("xxx", "yyy");
		tmp.delete();
		final InputStream in = getClass().getResourceAsStream("/artifacts.json");
		final OutputStream out = new FileOutputStream(tmp);
		IOUtils.copy( in ,  out );
		out.close();
		in.close();

		long sum = 0;
		long count = 0;
		for ( int i = 0 ; i < 80 ; i++ ) {

			long start = System.currentTimeMillis();
			try ( FlatFileStorage storage = new FlatFileStorage(tmp, IAPIClient.Protocol.JSON) ) {
				List<VersionInfo> results = storage.getAllVersions();
				long end = System.currentTimeMillis();
				if ( i >= 60 ) {
					sum += (end-start);
					count++;
				}
//				System.out.println("Loaded "+results.size()+" in "+(end-start)+" ms");
			}
		}
		System.out.println("Average time json: "+(sum/(float)count)+" ms");
	}
	
	@Test
	public void testRoundTrip() throws Exception {
		final File tmp = File.createTempFile("xxx", "yyy");
		tmp.delete();
		final InputStream in = getClass().getResourceAsStream("/artifacts.json");
		final OutputStream out = new FileOutputStream(tmp);
		IOUtils.copy( in ,  out );
		out.close();
		in.close();
		
		final File binaryFile = new File( tmp.getAbsolutePath()+".bin" );
		binaryFile.deleteOnExit();

		FlatFileStorage.convert( tmp, Protocol.JSON, binaryFile, Protocol.BINARY );
		
		FlatFileStorage storage1 = new FlatFileStorage(tmp,Protocol.JSON);
		FlatFileStorage storage2 = new FlatFileStorage(binaryFile,Protocol.BINARY);
		
		assertEquals( storage1.getAllVersions() , storage2.getAllVersions() );
	}
	
	@Test
	public void testLoadTimeBinary() throws Exception {

		final File tmp = File.createTempFile("xxx", "yyy");
		tmp.delete();
		final InputStream in = getClass().getResourceAsStream("/artifacts.json");
		final OutputStream out = new FileOutputStream(tmp);
		IOUtils.copy( in ,  out );
		out.close();
		in.close();
		
		final File binaryFile = new File( tmp.getAbsolutePath()+".bin" );
		binaryFile.deleteOnExit();

		FlatFileStorage.convert( tmp, Protocol.JSON, binaryFile, Protocol.BINARY );

		long sum = 0;
		long count = 0;
		for ( int i = 0 ; i < 80 ; i++ ) {

			long start = System.currentTimeMillis();
			try ( FlatFileStorage storage = new FlatFileStorage(binaryFile,Protocol.BINARY) ) {
				List<VersionInfo> results = storage.getAllVersions();
				long end = System.currentTimeMillis();
				if ( i >= 60 ) {
					sum += (end-start);
					count++;
				}
//				System.out.println("Loaded "+results.size()+" in "+(end-start)+" ms");
			}
		}
		System.out.println("Average time binary: "+(sum/(float)count)+" ms");
	}
}
