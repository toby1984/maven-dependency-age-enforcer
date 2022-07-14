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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.VersionInfo;
import de.codesourcery.versiontracker.common.server.APIImpl;
import de.codesourcery.versiontracker.common.server.APIImpl.Mode;
import de.codesourcery.versiontracker.common.server.VersionTracker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RetrievalTest
{
    @BeforeEach
    public void setup() throws Exception {
        // copy artifacts to temp location
        final File tmpOut = File.createTempFile("unittest", "suffix");
        tmpOut.deleteOnExit();
        try ( InputStream in= getClass().getResourceAsStream("/artifacts.json" ) ) 
        {
            Files.copy( in , tmpOut.toPath(),StandardCopyOption.REPLACE_EXISTING); 
        }
        
        System.setProperty( APIImpl.SYSTEM_PROPERTY_ARTIFACT_FILE , tmpOut.getAbsolutePath() );
        
        APIImplHolder.mode = Mode.CLIENT; // disable background update thread
        
        // force initialization
        APIImplHolder.getInstance().getImpl();        
    }
    
    @Test
    public void test() throws Exception {
        
        APIImpl impl = APIImplHolder.getInstance().getImpl(); 
        final VersionTracker tracker = impl.getVersionTracker();
        
        final Artifact artifact = new Artifact();
        // net.ftlines.wicket-source:wicket-source:7.0.0:jar
        artifact.groupId = "net.ftlines.wicket-source";
        artifact.artifactId = "wicket-source";
        artifact.version = "7.0.0";
        artifact.type= "jar";
        Map<Artifact, VersionInfo> map = tracker.getVersionInfo( Collections.singletonList( artifact ) , info -> true );
        VersionInfo result = map.get( artifact );
        Assertions.assertNotNull(result,"Got no result?");
    }
}
