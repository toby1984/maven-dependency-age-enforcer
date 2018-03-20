package de.codesourcery.versiontracker.server;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.Before;
import org.junit.Test;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactMap;
import de.codesourcery.versiontracker.common.VersionInfo;

public class CachingStorageDecoratorTest 
{
    private static final char[] LETTERS = "abcdefghijklmnopqrstuvwxyz".toCharArray(); 
    private static final char[] DIGITS = "0123456789".toCharArray();

    private static boolean DELETE_FILE_AFTER_TEST = true;
    
    private Random rnd;
    
    private List<VersionInfo> createVersionInfos(Random rnd,int count) 
    {
        final List<VersionInfo> result = new ArrayList<>();
        final ArtifactMap<VersionInfo> map = new ArtifactMap<>();
        while ( map.size() < count ) 
        {
            String groupId,artifactId;
            do {
                groupId= createGroupId(rnd,5,25);
                artifactId = createArtifactId(rnd,10,15);
            } while ( map.contains( groupId, artifactId ) );
            
            final Artifact artifact = new Artifact();
            artifact.groupId = groupId;
            artifact.artifactId = artifactId;
            artifact.version = createVersionNumber(rnd);
            final VersionInfo info = new VersionInfo();
            info.artifact = artifact;
            map.put( groupId, artifactId, info );
            result.add(info);
        }
        return result;
    }
    
    @Before
    public void setup() {
        rnd = new Random(0xdeadbeef);
    }

    private String createGroupId(Random rnd,int minLen,int maxLen) {
        return createString(rnd,minLen,maxLen,'.');
    }
    
    private String createArtifactId(Random rnd,int minLen,int maxLen) {
        return createString(rnd,minLen,maxLen,'_');
    }    
    
    private String createVersionNumber(Random rnd) 
    {
        final int partCount = 1+rnd.nextInt( 4 );
        final StringBuilder result = new StringBuilder();
        for ( int i = 0 ; i < partCount ; i++ ) 
        {
            if ( i == 3 ) { // create snapshot version
                result.append("-");
                result.append( randomString(rnd,3,7,DIGITS) );
            } else {
                if ( i > 0 ) {
                    result.append('.');
                }         
                result.append( randomString(rnd,1,2,DIGITS) );                
            }
        }
        return result.toString();
    }
    
    private final String randomString(Random rnd,int minLen,int maxLen,char[] choices) {
        final StringBuilder result = new StringBuilder();
        final int len = minLen + rnd.nextInt( maxLen-minLen );
        for ( int i = 0 ; i < len ; i++ ) {
            result.append( choices[ rnd.nextInt( choices.length ) ] );
        }
        return result.toString();
    }
    
    private String createString(Random rnd,int minLen,int maxLen,char specialLetter) {

        final int len = minLen+rnd.nextInt( maxLen-minLen );
        final StringBuilder result = new StringBuilder();
        char previousChar = specialLetter;
        for ( int i = 0 ; i < len ; i++ ) 
        {
            if ( i == 0 ) 
            {
                // first char always needs to be a letter
                previousChar = LETTERS[ rnd.nextInt( LETTERS.length ) ]; 
            } 
            else 
            {
                float factor = rnd.nextFloat();
                if ( factor > 0.95 && previousChar != specialLetter ) {
                    previousChar = specialLetter;
                } else {
                    if ( factor > 0.95 ) {
                        previousChar = DIGITS[ rnd.nextInt( DIGITS.length ) ]; 
                    } else {
                        previousChar = LETTERS[ rnd.nextInt( LETTERS.length ) ]; 
                    }
                }
                result.append( previousChar );
            }
        }
        return result.toString();
    }    

    @Test
    public void testNoCaching() throws IOException {

        final File file = new File("/tmp/artifactTest.noCache");
        if ( DELETE_FILE_AFTER_TEST ) {
            file.delete();
        }
        try 
        {
            final FlatFileStorage storage = new FlatFileStorage( file );
            final List<VersionInfo> items = createVersionInfos(rnd,1000);
            
            final long start = System.currentTimeMillis();
            storage.saveOrUpdate( items );
            
            long timestamp = ZonedDateTime.now().toEpochSecond()*1000;
            for ( int i = 0 ; i < 1000 ; i++ ) 
            {
                final int idx = rnd.nextInt( items.size() );
                Optional<VersionInfo> item = storage.getVersionInfo( items.get(idx).artifact );
                item.get().lastRepositoryUpdate = Instant.ofEpochMilli( timestamp ).atZone( ZoneId.systemDefault() );
                storage.saveOrUpdate( item.get() );
            }
            final long end = System.currentTimeMillis();
            System.out.println("Elapsed (NO caching): "+(end-start)+" ms");
        } 
        finally 
        {
            if ( DELETE_FILE_AFTER_TEST ) {
                file.delete();
                file.deleteOnExit();
            } else {
                System.out.println("Warning - test configured to NOT delete generated file "+file.getAbsolutePath()+" on teardown");
            }
        }
    }
    
    @Test
    public void testCaching() throws Exception {

        final File file = new File("/tmp/artifactTest.withCache");
        if ( DELETE_FILE_AFTER_TEST ) {
            file.delete();
        }
        
        final CachingStorageDecorator storage = new CachingStorageDecorator( new FlatFileStorage( file ) );
        storage.startThread();
        long start = 0; 
        try 
        {
            final List<VersionInfo> items = createVersionInfos(rnd,1000);
            
            start = System.currentTimeMillis();
            storage.saveOrUpdate( items );
            
            long timestamp = ZonedDateTime.now().toEpochSecond()*1000;            
            for ( int i = 0 ; i < 1000 ; i++ ) 
            {
                final int idx = rnd.nextInt( items.size() );
                Optional<VersionInfo> item = storage.getVersionInfo( items.get(idx).artifact );
                item.get().lastRepositoryUpdate = Instant.ofEpochMilli( timestamp ).atZone( ZoneId.systemDefault() );
                storage.saveOrUpdate( item.get() );
            }
            final long end = System.currentTimeMillis();
            System.out.println("Elapsed (caching): "+(end-start)+" ms");
        } 
        finally 
        {
            final long end2 = System.currentTimeMillis();
            System.out.println("Elapsed (caching,after flush()): "+(end2-start)+" ms");
            try {
                storage.close();
            } 
            finally 
            {
                if ( DELETE_FILE_AFTER_TEST ) {
                    file.delete();
                    file.deleteOnExit();
                } else {
                    System.out.println("Warning - test configured to NOT delete generated file "+file.getAbsolutePath()+" on teardown");
                }
            }
        }
    }    
}
