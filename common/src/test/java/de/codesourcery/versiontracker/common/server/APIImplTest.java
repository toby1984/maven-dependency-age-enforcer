package de.codesourcery.versiontracker.common.server;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.Blacklist;
import de.codesourcery.versiontracker.common.QueryRequest;
import de.codesourcery.versiontracker.common.QueryResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class APIImplTest {

    @Test
    public void testClientMode() throws InterruptedException {

        final APIImpl api = new APIImpl( APIImpl.Mode.CLIENT );
        api.setRegisterShutdownHook( false );

        final QueryRequest query = new QueryRequest();
        query.blacklist = new Blacklist();

        final Artifact art1 = new Artifact();
        art1.groupId = "de.codesourcery";
        art1.artifactId = "version-tracker";
        art1.version = "1.0.0";
        query.artifacts = List.of( art1 );

        final QueryResponse response = api.processQuery( query );

    }
}