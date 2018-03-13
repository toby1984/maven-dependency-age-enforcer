package de.codesourcery.versiontracker;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLConnection;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class MavenCentralVersionProvider implements IVersionProvider
{
    private static final Logger LOG = LogManager.getLogger(MavenCentralVersionProvider.class);

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("UTC"));
    
    private String serverBase = "http://repo1.maven.org/maven2/";

    private final XPathExpression latestSnapshot;
    private final XPathExpression latestRelease;
    private final XPathExpression lastUpdateDate;

    public MavenCentralVersionProvider() 
    {
        final XPathFactory factory = XPathFactory.newInstance();
        final XPath xpath = factory.newXPath();

        try {
            latestSnapshot = xpath.compile("/metadata/versioning/latest[text()]");
            latestRelease = xpath.compile("/metadata/versioning/release[text()]");
            lastUpdateDate = xpath.compile("/metadata/versioning/lastUpdated");
        } catch (XPathExpressionException e) {
            throw new RuntimeException(e);
        }        
    }

    private String artifactPath(Artifact artifact) 
    {
        // /$groupId[0]/../${groupId[n]/$artifactId/$version/$artifactId-$version.$extension

        // /$groupId[0]/../$groupId[n]/$artifactId/$version/$artifactId-$version-$classifier.$extension

        if ( artifact.classifier != null ) {
            return artifact.groupId.replace('.','/')+"/"+artifact.artifactId+"/"
                    +artifact.version+"/"+artifact.artifactId+"-"+artifact.version+"-"+artifact.classifier+"."+artifact.type;            
        } 
        return artifact.groupId.replace('.','/')+"/"+artifact.artifactId+"/"
        +artifact.version+"/"+artifact.artifactId+"-"+artifact.version+"."+artifact.type;
    }

    private String metaDataPath(Artifact artifact) {
        return artifact.groupId.replace('.','/')+"/"+artifact.artifactId+"/maven-metadata.xml";
    }

    public static void main(String[] args) throws IOException
    {
        Artifact test = new Artifact();
        test.groupId = "junit";
        test.artifactId = "junit";
        System.out.println("GOT: "+new MavenCentralVersionProvider().getLatestVersion( test ));
    }

    @Override
    public Optional<VersionInfo> getLatestVersion(Artifact artifact) throws IOException
    {
        final URL url = new URL( serverBase+metaDataPath( artifact ) );
        URLConnection con = url.openConnection();
        try
        {
            con.connect();
            final InputStream in = con.getInputStream();
            try 
            { 
                final Document document = parseXML( con.getInputStream() );
                System.out.println( toString(document) );
                String snapshot = latestSnapshot.evaluate( document );
                System.out.println("latest = "+latestSnapshot);
                String release = latestRelease.evaluate( document );
                System.out.println("release = "+latestRelease);
                String lastChangeString = lastUpdateDate.evaluate( document );
                
                final ZonedDateTime lastChangeDate = ZonedDateTime.parse( lastChangeString, DATE_FORMATTER);
                System.out.println("last change = "+lastChangeString);
                
                VersionInfo info = new VersionInfo();
                info.artifact = artifact;
                info.latestReleaseVersion = release;
                info.latestSnapshotVersion = snapshot;
                info.lastRepositoryUpdate = lastChangeDate;
                info.creationDate = ZonedDateTime.now();
                return Optional.of( info );
            } 
            finally 
            {
                try { in.close(); } catch(IOException e) { /* ok */ }
            }
        } catch(FileNotFoundException e) {
            return Optional.empty();
        } 
        catch (Exception e) {
            if ( e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException(e.getMessage(),e);
        }
    }

    private static String toString(Document doc) 
    {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.getBuffer().toString();
        } 
        catch(Exception e) 
        {
            throw new RuntimeException(e);
        }
    }

    public static Document parseXML(InputStream inputStream) throws ParserConfigurationException, SAXException, IOException
    {
        if ( inputStream == null ) {
            throw new IOException("input stream cannot be NULL");
        }

        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        final DocumentBuilder builder = factory.newDocumentBuilder();

        // set fake EntityResolver , otherwise parsing is incredibly slow (~1 sec per file on my i7)
        // because the parser will download the DTD from the internets...
        builder.setEntityResolver( new DummyResolver() );
        return builder.parse( inputStream);
    }

    private static final class DummyResolver implements EntityResolver {

        @Override
        public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException
        {
            final ByteArrayInputStream dummy = new ByteArrayInputStream(new byte[0]);
            return new InputSource(dummy);
        }
    }    
}
