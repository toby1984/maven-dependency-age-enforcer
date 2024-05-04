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
package de.codesourcery.versiontracker.enforcerrule;

import de.codesourcery.versiontracker.client.api.IAPIClient;
import de.codesourcery.versiontracker.client.api.IAPIClient.Protocol;
import de.codesourcery.versiontracker.client.api.local.LocalAPIClient;
import de.codesourcery.versiontracker.client.api.remote.RemoteApiClient;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.ArtifactResponse.UpdateAvailable;
import de.codesourcery.versiontracker.common.Blacklist;
import de.codesourcery.versiontracker.common.Blacklist.VersionMatcher;
import de.codesourcery.versiontracker.xsd.IgnoreVersion;
import de.codesourcery.versiontracker.xsd.Rule;
import de.codesourcery.versiontracker.xsd.Ruleset;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.maven.enforcer.rule.api.AbstractEnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.project.MavenProject;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.text.MessageFormat;
import java.text.ParseException;
import java.time.Duration;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A custom rule for the 
 * <a href="https://maven.apache.org/enforcer/maven-enforcer-plugin/ maven-enforcer-plugin">Maven Enforcer Plugin</a> 
 * that supports warning and failing the build when project dependencies are outdated by a configurable amount of time.
 * 
 * @author tobias.gierke@code-sourcery.de
 */
@Named("dependencyAgeRule")
public class DependencyAgeRule extends AbstractEnforcerRule
{
    private static final String MAX_AGE_PATTERN_STRING = "(\\d+)\\s*([dwmy]|(day|days|week|weeks|month|months|year|years))";

    public static final Pattern MAX_AGE_PATTERN = Pattern.compile(MAX_AGE_PATTERN_STRING,Pattern.CASE_INSENSITIVE);

    // When running in client mode (=without the proxy servlet doing the actual work)
    // we need to use a global lock here to prevent concurrent Maven plugin executions (using the -T option) 
    // from writing to the metadata backing store file concurrently.
    
    private static final Object GLOBAL_LOCK = new Object();
    
    // @GuardedBy(CLIENTS)
    private static final Map<String,RemoteApiClient> CLIENTS = new HashMap<>();
    
    private static final Object LOCAL_API_CLIENT_LOCK = new Object();
    
    // @GuardedBy(LOCAL_API_CLIENT_LOCK)
    private static LocalAPIClient LOCAL_API_CLIENT;
    
    private static final JAXBContext jaxbContext;
    
    static 
    {
        try {
			jaxbContext = JAXBContext.newInstance( Ruleset.class);
		} catch ( JAXBException e) {
			throw new RuntimeException(e);
		}
    }

    private MavenProject project;

    Age parsedMaxAge;
    Age parsedWarnAge;

    // rule configuration properties

    /**
     * Dependency age at which to start printing warnings.
     * <p>
     * Executing this rule will print warnings for all project dependencies
     * whose version is older than <code>warnAge</code>.
     * </p>
     * Supported syntax is "1d", "1 day", "3 days", "2 weeks", "1 month", "1m", "1 year", "1y".
     */
    @SuppressWarnings("unused")
    String warnAge;

    /**
     * Dependency age at which to fail the build.
     * <p>
     * Executing this rule will fail the build when at least one project dependency
     * is older than <code>maxAge</code>.
     * </p>
     * Supported syntax is "1d", "1 day", "3 days", "2 weeks", "1 month", "1m", "1 year", "1y".
     */
    @SuppressWarnings("unused")
    String maxAge;

    /**
     * API endpoint to contact for fetching artifact version metadata.  
     */
    @SuppressWarnings("unused")
    String apiEndpoint;

    /**
     * Enable verbose output.
     */
    @SuppressWarnings("unused")
    boolean verbose;

    /**
     * Enable debug (very verbose) output.
     */
    @SuppressWarnings("unused")
    boolean debug;

    /**
     * Optional path to XML file that describes what artifact versions to ignore. 
     */
    File rulesFile;

    /**
     * Whether to fail when an artifact could not be found in the repository.
     */
    boolean failOnMissingArtifacts=true;
    
    boolean binaryProtocol=true;

    /**
     * Whether to look for the rules XML file in parent directories
     * if the file is not found in the specified location.
     */
    @SuppressWarnings("unused")
    boolean searchRulesInParentDirectories;

    private enum AgeUnit
    {
        DAYS,WEEKS,MONTHS,YEARS;

        public static AgeUnit parse(String unit) {
            if ( StringUtils.isBlank( unit ) ) {
                throw new IllegalArgumentException("age unit must not be NULL/blank");
            }
            final String s = unit.trim().toLowerCase();
            if ( "d".equals(s) || "day".equals(s) || "days".equals(s) ) {
                return DAYS;
            }
            if ( "w".equals(s) || "week".equals(s) || "weeks".equals(s) ) {
                return WEEKS;
            }
            if ( "m".equals(s) || "month".equals(s) || "months".equals(s) ) {
                return MONTHS;
            }   
            if ( "y".equals(s) || "year".equals(s) || "years".equals(s) ) {
                return YEARS;
            }               
            throw new IllegalArgumentException("Unknown age unit '"+unit+"'");
        }
    }

    private record Age(int value, AgeUnit unit) {

        public Period toPeriod() {
                return switch ( unit ) {
                    case DAYS -> Period.ofDays( value );
                    case MONTHS -> Period.ofMonths( value );
                    case WEEKS -> Period.ofWeeks( value );
                    case YEARS -> Period.ofYears( value );
                };
            }

            @Override
            public String toString() {
                final boolean plural = value > 1;
                return switch ( unit ) {
                    case DAYS -> value + " " + (plural ? "days" : "day");
                    case MONTHS -> value + " " + (plural ? "months" : "month");
                    case WEEKS -> value + " " + (plural ? "weeks" : "week");
                    case YEARS -> value + " " + (plural ? "years" : "year");
                };
            }

            public boolean isExceeded(Duration duration, ZonedDateTime now) {
                final Duration thisDuration = Duration.between( now, now.plus( toPeriod() ) );
                return duration.compareTo( thisDuration ) > 0;
            }
        }

    public DependencyAgeRule() {
    }

    private boolean isTooOld(ArtifactResponse response,Age threshold)
    {
        if ( response.hasCurrentVersion() &&
                response.hasLatestVersion() )
        {
            if ( response.currentVersion.hasReleaseDate() && response.latestVersion.hasReleaseDate() &&
                 ! Objects.equals( response.currentVersion.versionString, response.latestVersion.versionString ) &&
                 response.currentVersion.releaseDate.compareTo( response.latestVersion.releaseDate ) <= 0 )
            {
                final ZonedDateTime now = currentTime();
                final Duration age = Duration.between( response.latestVersion.releaseDate , now );
                if ( threshold.isExceeded( age, now) )
                {
                    final String msg = "Age threshold exceeded for " + response.artifact + ", age is " + age + " but threshold is " + threshold;
                    getLog().debug(msg);
                    return true;
                }
            } 
            else 
            {
                if( ! response.currentVersion.hasReleaseDate() ) {
                    getLog().warn("Unable to determine current release date for version '"+response.currentVersion.versionString+"' of "+response.artifact);
                }
                if( ! response.latestVersion.hasReleaseDate() ) {
                    getLog().warn("Unable to determine latest release date for version '"+response.latestVersion.versionString+"' of "+response.artifact);
                }                
            }
        }
        return false;
    }

    @Override
    public void execute() throws EnforcerRuleException {
    	long start = System.currentTimeMillis();
    	try {
    		executeInternal();
    	} 
    	finally 
    	{
    	    if ( debug ) {
    	        final long elapsed = System.currentTimeMillis() - start;
    	        getLog().info("RULE TIME: "+elapsed+" ms");
    	    }
    	}
    }
    
    public void executeInternal() throws EnforcerRuleException
    {
        setup();
        
        if ( StringUtils.isBlank( apiEndpoint ) ) 
        {
            synchronized( GLOBAL_LOCK ) 
            {
                doExecute();
            }
        } else {
            doExecute();
        }
    }
    
    private void setup() throws EnforcerRuleException
    {
        if ( StringUtils.isNotBlank( maxAge ) ) {
            parsedMaxAge = parseAge( "maxAge", maxAge );
        }
        if ( StringUtils.isNotBlank( warnAge ) ) {
            parsedWarnAge = parseAge( "warnAge", warnAge );
        }

        if ( parsedWarnAge == null && parsedMaxAge == null ) {
            fail("Configuration error - at least one of 'maxAge' or 'warnAge' needs to be set");
        }

        if ( parsedWarnAge != null && parsedMaxAge != null ) {
            final ZonedDateTime now = ZonedDateTime.now();
            if ( now.plus( parsedWarnAge.toPeriod() ).isAfter( now.plus( parsedMaxAge.toPeriod() ) ) ) {
                fail("Configuration error - 'warnAge' needs to be less than 'maxAge'");
            }
        }
        getLog().debug("==== Rule executing with API endpoint = "+apiEndpoint);
        if ( parsedWarnAge != null )  {
            getLog().debug("==== Rule executing with warnAge = "+parsedWarnAge);
        }
        if ( parsedMaxAge != null ) {
            getLog().debug("==== Rule executing with maxAge = "+parsedMaxAge);
        }        
    }    

    private void doExecute() throws EnforcerRuleException
    {
        final Set<org.apache.maven.artifact.Artifact> mavenArtifacts = project.getDependencyArtifacts();

        if ( ! mavenArtifacts.isEmpty() ) 
        {
            final Blacklist bl;
            try {
                bl = loadBlacklist();
            } catch (JAXBException | ParseException e1) {
                fail("Failed to parse rules.xml ("+e1.getMessage()+")",e1);
                throw new RuntimeException("Unreachable code reached"); // fail() never returns
            }

            final List<Artifact> artifacts = new ArrayList<>();
            for ( org.apache.maven.artifact.Artifact ma : mavenArtifacts ) {
                Artifact a = new Artifact();
                a.groupId = ma.getGroupId();
                a.artifactId = ma.getArtifactId();
                a.version = ma.getVersion();
                a.type = ma.getType();
                a.setClassifier(ma.getClassifier());
                if ( verbose ) {
                    getLog().info("Project depends on "+a);
                } else {
                    getLog().debug("Project depends on "+a);
                }
                if ( bl == null || ! bl.isAllVersionsBlacklisted( a.groupId, a.artifactId) ) {
                    artifacts.add( a );
                } else {
                    if ( verbose ) {
                        getLog().warn("All artifact versions ignored by blacklist: "+a);
                    }
                }
            }

            final List<ArtifactResponse> result;
            final IAPIClient client; 
            if ( StringUtils.isBlank( apiEndpoint ) ) 
            {
                getLog().warn("No API endpoint configured, running locally");
                client = getLocalAPIClient(debug);
            } 
            else 
            {
            	final Protocol protocol = binaryProtocol ? Protocol.BINARY : Protocol.JSON;
            	if ( verbose ) {
            		getLog().info("Using "+protocol+" protocol");
            	}
                client = getRemoteAPIClient(apiEndpoint,protocol,debug);
            }
            try 
            {
                if ( StringUtils.isBlank( apiEndpoint ) ) {
                    getLog().info("Querying metadata for "+artifacts.size()+" artifacts");
                } else {
                    getLog().info("Querying metadata for "+artifacts.size()+" artifacts from "+apiEndpoint);
                }
                result = client.query(artifacts,bl);
            } 
            catch (Exception e) 
            {
                fail("Failed to query version information from '"+apiEndpoint+"': "+e.getMessage(),e);
                throw new RuntimeException("Unreachable code reached");
            } 
            boolean failBecauseAgeExceeded = false;
            boolean artifactsNotFound = false;
            ZonedDateTime earliestOffendingRelease = null;
            for ( ArtifactResponse resp : result ) 
            {
                if ( resp.updateAvailable == UpdateAvailable.NOT_FOUND ) {
                    artifactsNotFound = true;
                    getLog().warn( "Failed to find metadata for artifact "+resp.artifact);
                    continue;
                }
                final boolean maxAgeExceeded = parsedMaxAge != null && isTooOld( resp, parsedMaxAge );
                final boolean warnAgeExceeded = parsedWarnAge != null && isTooOld( resp, parsedWarnAge );
                failBecauseAgeExceeded |= maxAgeExceeded;
                if ( warnAgeExceeded && ! maxAgeExceeded ) {
                    printMessage(resp,false); // log warning
                    if ( earliestOffendingRelease == null || resp.latestVersion.releaseDate.isBefore( earliestOffendingRelease) ) {
                        earliestOffendingRelease = resp.latestVersion.releaseDate;
                    }
                }
                if ( maxAgeExceeded) 
                {
                    printMessage(resp,true); // log error
                }
            }
            if ( failBecauseAgeExceeded ) {
                fail("One or more dependencies of this project are older than the allowed maximum age ("+parsedMaxAge+")");
            }
            if ( artifactsNotFound && failOnMissingArtifacts ) {
                fail("Failed to find metadata for one or more dependencies of this project");
            }
            if ( earliestOffendingRelease != null && parsedMaxAge != null )
            {
                final ZonedDateTime now = ZonedDateTime.now();
                final ZonedDateTime failureTime = earliestOffendingRelease.plus( parsedMaxAge.toPeriod() );
                final Duration remainingTime = Duration.between(now,failureTime);
                final long millis = remainingTime.toMillis();
                final DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDateTime( FormatStyle.LONG, FormatStyle.LONG  );
                getLog().warn("===========================================");
                getLog().warn("= Your build will start FAILING on "+formatter.format( failureTime )+" which is in "+
                        DurationFormatUtils.formatDurationWords(millis,true,true));
                getLog().warn("===========================================");
            }
        }
    }

    private void printMessage(ArtifactResponse resp,boolean printAsError) {
        final String fmt = "Artifact {0} is too old, current version {1} was released on {2} but latest version "+
                " is {3} which was released on {4}";
        final String msg = MessageFormat.format(fmt,
                resp.artifact.toString(),
                resp.currentVersion.versionString,
                prettyPrint( resp.currentVersion.releaseDate ),
                resp.latestVersion.versionString,
                prettyPrint( resp.latestVersion.releaseDate )
                );

        if ( printAsError ) {
            getLog().error( msg );
        } else {
            getLog().warn( msg );
        }
    }

    private void fail(String msg,Throwable t) throws EnforcerRuleException {
        getLog().error(msg);
        throw new EnforcerRuleException(msg,t);		
    }

    private void fail(String msg) throws EnforcerRuleException {
        getLog().error(msg);
        throw new EnforcerRuleException(msg);		
    }

    private static String prettyPrint(ZonedDateTime dt) 
    {
        if ( dt == null ) {
            return "n/a";
        }
        ZonedDateTime converted = dt.withZoneSameInstant( ZoneId.systemDefault() ); 
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG,FormatStyle.LONG).format( converted );
    }

    private Age parseAge(String configKey, String configValue) throws EnforcerRuleException 
    {
        Validate.notBlank( configKey, "configKey must not be null or blank");
        Validate.notNull( configValue, "configValue must not be null" );
        try 
        {
            final Matcher m = MAX_AGE_PATTERN.matcher( configValue.trim() );
            if ( m.matches() ) 
            {
                final int value = Integer.parseInt( m.group(1) );
                if ( value < 0 ) {
                    final String message = "'"+configKey+"' must be >= 0 but was "+value;
                    getLog().error( message );
                    throw new EnforcerRuleException(message);                      
                }
                final AgeUnit unit = AgeUnit.parse( m.group(2) );
                return new Age(value,unit);
            }
            final String message = "Configuration error - not a valid '"+configKey+"' pattern: '"+configValue+"', must match regex '"+MAX_AGE_PATTERN_STRING+"'";
            getLog().error( message );
            throw new EnforcerRuleException(message);
        } 
        catch(EnforcerRuleException e) {
            throw e;
        }
        catch(Exception e) 
        {
            final String message = "Configuration error - not a valid '"+configKey+"' pattern: '"+configValue+"', must match regex '"+MAX_AGE_PATTERN_STRING+"'";
            getLog().error( message );
            throw new EnforcerRuleException(message,e);
        }
    }

    @Override
    public String getCacheId()
    {
        return warnAge+apiEndpoint+verbose+debug+rulesFile+maxAge;
    }

    private static File getParent(File file) 
    {
        return file.getParentFile();
    }

    private Blacklist loadBlacklist() throws JAXBException,ParseException, EnforcerRuleException {

        if ( rulesFile == null ) {
            return null;
        }

        if ( ! rulesFile.exists() && searchRulesInParentDirectories ) 
        {
            if ( verbose ) {
                getLog().info("Rules file "+rulesFile.getAbsolutePath()+" does not exist, searching parent folders");
            }
            String fileName = rulesFile.getName();
            File folder;
            do 
            {
                folder = getParent( rulesFile.getParentFile() );                    
                rulesFile = new File(folder,fileName);
                if ( debug ) {
                    getLog().info("Trying "+rulesFile.getAbsolutePath());
                }                 
                if ( rulesFile.exists() && rulesFile.isFile() ) 
                {
                    break;
                }
            } while ( folder != null && folder.toPath().getNameCount() != 0 );
        }
        if ( ! rulesFile.exists() || ! rulesFile.isFile() ) {
            fail(rulesFile.getAbsolutePath()+" does not exist or is no regular file");
        }     

        if ( verbose ) {
            getLog().info("Using XML rules file "+rulesFile.getAbsolutePath());
        }        

        final Blacklist blacklist = new Blacklist();
        final Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
        final Ruleset result = (Ruleset) jaxbUnmarshaller.unmarshal(rulesFile);
        assertSupportedComparisonMethod(result.getComparisonMethod());
        List<Rule> rules;
        rules = result.getRules() != null ? result.getRules().getRule() : null;
        if ( rules != null ) {
            for ( Rule r : rules ) 
            {
                assertSupportedComparisonMethod(r.getComparisonMethod());
                final boolean hasIgnoredVersions = r.getIgnoreVersions() != null && r.getIgnoreVersions().getIgnoreVersion() != null;
                if ( hasIgnoredVersions ) {
                    assertMatcherTypeSupported(r.getIgnoreVersions().getIgnoreVersion());
                }
                if ( hasIgnoredVersions && StringUtils.isBlank( r.getArtifactId() ) ) 
                {
                    for ( IgnoreVersion v : r.getIgnoreVersions().getIgnoreVersion() ) {
                        blacklist.addIgnoredVersion(r.getGroupId(),v.getValue(),VersionMatcher.fromString( v.getType() ) );
                    }
                } 
                else if ( hasIgnoredVersions ) 
                {
                    for ( IgnoreVersion v : r.getIgnoreVersions().getIgnoreVersion() ) {
                        blacklist.addIgnoredVersion(r.getGroupId(),r.getArtifactId(),v.getValue(),VersionMatcher.fromString( v.getType() ) );
                    }
                }                    
            }
        }
        if ( result.getIgnoreVersions() != null ) {
            assertMatcherTypeSupported( result.getIgnoreVersions().getIgnoreVersion() );
        }

        // register global blacklist
        if ( result.getIgnoreVersions() != null && result.getIgnoreVersions().getIgnoreVersion() != null ) {
            for ( IgnoreVersion v : result.getIgnoreVersions().getIgnoreVersion() ) 
            {
                blacklist.addIgnoredVersion(v.getValue(),VersionMatcher.fromString( v.getType() ) );
            }
        }
        return blacklist;
    }

    private void assertMatcherTypeSupported(List<IgnoreVersion> list) throws ParseException 
    {
        if ( list != null ) 
        {
            for ( IgnoreVersion v : list ) 
            {
                final String matcher = v.getType();
                if (matcher != null && ! (matcher.equals("regex") | matcher.equals("exact") ) ) {
                    throw new ParseException("Sorry, rules file "+rulesFile.getAbsolutePath()+" "
                            + "contains unsupported value '"+matcher+"'for 'type' attribute of <ignoreVersion/> tag",-1);
                }
            }
        }
    }    

    private void assertSupportedComparisonMethod(String method) throws ParseException 
    {
        if (method != null && ! method.equals("maven") ) {
            throw new ParseException("Sorry, rules file "+rulesFile.getAbsolutePath()+" contains custom comparison method '"+method+"' but custom comparison methods are not supported by this plugin.",-1);
        }
    }

    // unit-testing hook
    protected IAPIClient getLocalAPIClient(boolean debug)
    {
        synchronized(LOCAL_API_CLIENT_LOCK) 
        {
            if ( LOCAL_API_CLIENT == null ) {
                LOCAL_API_CLIENT = new LocalAPIClient();
                LOCAL_API_CLIENT.setDebugMode( debug );
                Runtime.getRuntime().addShutdownHook( new Thread( () -> {
                    synchronized(LOCAL_API_CLIENT_LOCK) 
                    {
                        try {
                            LOCAL_API_CLIENT.close();
                        } catch (Exception e) {
                            getLog().debug( "Caught exception during close(): " + e.getMessage() );
                        } finally {
                            LOCAL_API_CLIENT = null;
                        }
                    }
                }));
            }
            return LOCAL_API_CLIENT;
        }
    }

    // unit-testing hook
    protected IAPIClient getRemoteAPIClient(String endpoint,Protocol protocol,boolean debug)
    {
        final String key = endpoint+protocol.name()+debug;
        final String callingThreadName = Thread.currentThread().getName();
        synchronized(CLIENTS) 
        {
            RemoteApiClient existing = CLIENTS.get( key );
            if ( existing == null ) {
                existing = new RemoteApiClient(endpoint,protocol);
                existing.setDebugMode( debug );
                CLIENTS.put(key, existing );
                
                Runtime.getRuntime().addShutdownHook( new Thread( () -> 
                {
                    if ( debug ) {
                        getLog().info("Shutting down HTTP client acquired by "+callingThreadName+" connecting to "+endpoint+" using "+protocol);
                    }
                    synchronized(CLIENTS) 
                    {
                        RemoteApiClient client = CLIENTS.get( key );
                        if ( client != null ) {
                            try {
                                client.close();
                            } catch (Exception e) {
                                getLog().debug( "Caught exception during close(): "+e.getMessage());
                            } finally {
                                CLIENTS.remove( key );
                            }
                        }
                    }
                }));                
            }
            return existing;
        }
    }

    // unit-testing hook
    protected ZonedDateTime currentTime() {
        return ZonedDateTime.now();
    }

    @Inject
    public void setProject(MavenProject project) {
        this.project = project;
    }
}