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
package de.codesourcery.versiontracker.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArtifactTest {

	@Test
	public void testVersionDetection() {
		
		assertTrue( Artifact.isReleaseVersion("1") );
		assertTrue( Artifact.isReleaseVersion("1.0") );
		assertTrue( Artifact.isReleaseVersion("1.0.0") );
		assertFalse( Artifact.isReleaseVersion("1.0.0-test1") );
		assertFalse( Artifact.isReleaseVersion("1-SNAPSHOT") );
		assertFalse( Artifact.isReleaseVersion("1.0-SNAPSHOT") );
		assertFalse( Artifact.isReleaseVersion("1.0.0-SNAPSHOT") );
	}
	
	@Test
	public void testVersionSorting() {
		
		final List<String> actual = new ArrayList<>( 
				Arrays.asList(
						"1", 
						"2", 
						"1.0", 
						"1.1", 
						"1.1-SNAPSHOT", 
						"1.2.1-SNAPSHOT", 
						"1.0.0-jdk9", 
						"3.0.0-jdk9", 
						"1.2" 
						) );
		
		actual.sort( Artifact.VERSION_COMPARATOR );
		
		final List<String> expected = Arrays.asList(
				"1",
				"1.0",
				"1.0.0-jdk9",
				"1.1",
				"1.1-SNAPSHOT",
				"1.2",
				"1.2.1-SNAPSHOT",
				"2",
				"3.0.0-jdk9"
				);
		actual.forEach( System.out::println );
		assertEquals(expected,actual);
	}	
}
