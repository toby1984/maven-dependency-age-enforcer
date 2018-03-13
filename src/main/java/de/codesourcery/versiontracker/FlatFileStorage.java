package de.codesourcery.versiontracker;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

public class FlatFileStorage implements IVersionStorage
{
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("UTC"));
    
    private File file;
    
    public FlatFileStorage() {
    }
    
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
        try ( final BufferedReader reader = new BufferedReader( new FileReader( file ) ) ) 
        {
            StringBuilder buffer = new StringBuilder();
            String line = "";
            while ( (line = reader.readLine()) != null ) {
                buffer.append( line );
            }
            final JSONObject json = JSONObject.fromObject( buffer.toString() );
            final JSONArray array = json.getJSONArray("data");
            for ( int i = 0 ; i < array.size() ; i++ ) 
            {
                final JSONObject object = array.getJSONObject( i );
                result.add( deserialize( object ) );
            }
        }
        return result;
    }
    
    @Override
    public synchronized void saveAll(List<VersionInfo> data) throws IOException 
    {
        final StringBuilder buffer = new StringBuilder();
        buffer.append("{ data : [");
        for (Iterator<VersionInfo> it = data.iterator(); it.hasNext();) 
        {
            final VersionInfo info = it.next();
            buffer.append( toJSON( info ) );
            if ( it.hasNext() ) 
            {
                buffer.append(",");
            }
        }
        buffer.append("]}");
        try ( final BufferedWriter writer = new BufferedWriter( new FileWriter( file ) ) ) {
            writer.write( buffer.toString() );
        }
    }
    
    @Override
    public synchronized void storeVersionInfo(VersionInfo info) throws IOException
    {
        List<VersionInfo> all = getAllVersions();
        all.removeIf( item -> item.artifact.matchesExcludingVersion( info.artifact) );
        all.add( info );
        saveAll( all );
    }
    
    private VersionInfo deserialize(JSONObject object) 
    {
        VersionInfo result = new VersionInfo();
        result.artifact = deserializeArtifact( object.getJSONObject("artifact"));
        result.creationDate = deserializeDate( object.getString("creationDate") );
        result.latestReleaseVersion = object.getString("latestReleaseVersion");
        result.lastSuccessDate = deserializeDate( object.getString("lastSuccessDate") );
        result.lastFailureDate = deserializeDate( object.getString("lastFailureDate") );
        result.latestSnapshotVersion = object.getString("latestSnapshotVersion");
        result.lastRepositoryUpdate = deserializeDate( object.getString("lastRepositoryUpdate") );
        return result;
    }    
    
    private ZonedDateTime deserializeDate(String input) 
    {
        if ( input == null ) {
            return null;
        }
        return ZonedDateTime.parse( input , DATE_FORMATTER );
    }
    
    private String toJSON(VersionInfo info) 
    {
        /*
    public Artifact artifact;
    public ZonedDateTime creationDate;
    public String latestReleaseVersion;
    public String latestSnapshotVersion;
    public ZonedDateTime releaseRepositoryUpdate;         
         */
        return "{"+
                "artifact : "+toJSON( info.artifact )+","+
                "creationDate : "+toJSON( info.creationDate )+","+
                "latestReleaseVersion : "+string( info.latestReleaseVersion )+","+
                "lastSuccessDate : "+toJSON( info.lastSuccessDate )+","+
                "lastFailureDate : "+toJSON( info.lastFailureDate )+","+
                "latestSnapshotVersion : "+string( info.latestSnapshotVersion )+","+
                "lastRepositoryUpdate : "+toJSON( info.lastRepositoryUpdate )+","+
               "}";
    }
    
    private Artifact deserializeArtifact(JSONObject object) 
    {
        Artifact result = new Artifact();
        result.artifactId = object.getString("latestReleaseVersion");
        result.classifier = object.getString("classifier");
        result.groupId = object.getString("groupId");
        result.type= object.getString("type");
        result.version = object.getString("version");
        return result;
    }    
    
    private String toJSON(Artifact artifact) {
        return "{"+
               "groupId : "+string(artifact.groupId)+","+
               "artifactId : "+string(artifact.artifactId)+","+
               "version : "+string(artifact.version)+","+
               "classifier : "+string(artifact.classifier)+","+
               "type : "+string(artifact.type)
                +"}";
    }
    
    private String toJSON(ZonedDateTime date) 
    {
        return date == null ? "null" : '"'+DATE_FORMATTER.format( date )+'"';
    }
    
    private String string(String s) {
        if ( s == null ) {
            return "null";
        }
        return escape(s);
    }
    
    private static String escape(String input) 
    {
        if ( input.contains("\"" ) ) {
            return input.replace("\"","\\\"");
        }
        return input;
    }

    @Override
    public synchronized Optional<VersionInfo> loadVersionInfo(Artifact artifact) throws IOException
    {
        return getAllVersions().stream().filter( item -> item.artifact.matchesExcludingVersion( artifact ) ).findFirst();
    }
}