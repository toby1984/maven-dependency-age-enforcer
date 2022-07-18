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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.regex.Pattern;

public class Blacklist implements IBlacklistCheck 
{
    public enum VersionMatcher
    {
        @JsonProperty("exact")        
        EXACT("exact"),
        @JsonProperty("regex")
        REGEX("regex");
        
        public final String text;
        
        VersionMatcher(String text) {
            this.text = text;
        }
        
        public static VersionMatcher fromString(String s) 
        {
            return switch ( s.toLowerCase() ) {
                case "exact" -> VersionMatcher.EXACT;
                case "regex" -> VersionMatcher.REGEX;
                default -> throw new IllegalArgumentException( "Unsupported version matcher type: '" + s + "'" );
            };
        }
    }
    
    private final List<VersionStringMatcher> globalIgnores = new ArrayList<>();

    // key is group ID
    private final Map<String,List<VersionStringMatcher>>  groupIdIgnores = new HashMap<>();
    private final Map<String,Map<String,List<VersionStringMatcher>>>  artifactIgnores = new HashMap<>();
    
    @Override
    public boolean equals(Object other) 
    {
    	if ( other instanceof Blacklist o ) {
            if ( ! equals(this.globalIgnores, o.globalIgnores ) ) {
    			return false;
    		}
    		if ( ! equals(this.groupIdIgnores,o.groupIdIgnores) ) {
    			return false;
    		}
    		if ( artifactIgnores.size() != o.artifactIgnores.size() ) {
    			return false;
    		}
    		for ( Entry<String, Map<String, List<VersionStringMatcher>>> entry1 : this.artifactIgnores.entrySet() ) {
    			final String key = entry1.getKey();
    			final Map<String, List<VersionStringMatcher>> map1 = entry1.getValue(); 
    			final Map<String, List<VersionStringMatcher>> map2 = o.artifactIgnores.get(key);
    			if ( ! equals(map1,map2) ) {
    				return false;
    			}
    		}
    		return true;
    	}
    	return false;
    }
    
    private static boolean equals(Map<String,List<VersionStringMatcher>> m1,Map<String,List<VersionStringMatcher>> m2 ) {
		if ( m1.size() != m2.size() ) {
			return false;
		}
		for ( Entry<String, List<VersionStringMatcher>> entry : m1.entrySet() ) {
			final List<VersionStringMatcher> l1 = entry.getValue();
			final List<VersionStringMatcher> l2 = m2.get( entry.getKey() );
			if ( ! equals(l1,l2) ) {
				return false;
			}
		}
		return true;
    }
    
    private static boolean equals(List<VersionStringMatcher> l1,List<VersionStringMatcher> l2) {
    	if ( l1 == null || l2 == null ) {
    		return l1 == l2;
    	}
    	if ( l1.size() != l2.size() ) {
    		return false;
    	}
   		for ( VersionStringMatcher m1 : l1 ) {
			if ( ! l2.stream().anyMatch( x -> x.equals( m1 ) ) )  {
				return false;
			}
		}
    	return true;
    }
    
    public void serialize(BinarySerializer serializer) throws IOException  
    {
        serializer.writeInt( globalIgnores.size() );
        for ( VersionStringMatcher matcher : globalIgnores ) {
            matcher.serialize(serializer);
        }
        
        serializer.writeInt( groupIdIgnores.size() );
        for (Entry<String, List<VersionStringMatcher>> entry : groupIdIgnores.entrySet() ) 
        {
            serializeMapEntry(entry,serializer);
        }
        
        serializer.writeInt( artifactIgnores.size() );
        for ( Entry<String, Map<String, List<VersionStringMatcher>>> entry : artifactIgnores.entrySet() ) 
        {
            serializer.writeString( entry.getKey() );
            final Map<String, List<VersionStringMatcher>> map = entry.getValue();
            serializer.writeInt( map.size() );
            for ( Entry<String, List<VersionStringMatcher>> entry2 : map.entrySet() ) 
            {
                serializeMapEntry(entry2,serializer);
            }
        }
    }
    
    private void serializeMapEntry(Entry<String, List<VersionStringMatcher>> entry,BinarySerializer serializer) throws IOException {
        serializer.writeString(entry.getKey());
        serializer.writeInt( entry.getValue().size() );
        for ( VersionStringMatcher matcher : entry.getValue() ) {
            matcher.serialize(serializer);
        }
    }
    
    private static void deserializeMapEntry(Map<String, List<VersionStringMatcher>> map,BinarySerializer serializer) throws IOException 
    {
        final String key = serializer.readString();
        int count = serializer.readInt();
        final List<VersionStringMatcher> list = new ArrayList<>( count );
        for ( ; count > 0 ; count-- ) {
            list.add( VersionStringMatcher.deserialize(serializer) );
        }
        map.put( key , list );
    } 
    
    public static Blacklist deserialize(BinarySerializer serializer) throws IOException  
    {
        final Blacklist result = new Blacklist();
        for ( int count = serializer.readInt() ; count > 0 ; count--) {
            result.globalIgnores.add( VersionStringMatcher.deserialize( serializer ) );
        }
        
        for ( int count = serializer.readInt() ; count > 0 ; count--) {
            deserializeMapEntry(result.groupIdIgnores,serializer);
        }
        
        for ( int count = serializer.readInt() ; count > 0 ; count-- ) 
        {
            final String key = serializer.readString();
            int count2 = serializer.readInt();
            final Map<String, List<VersionStringMatcher>> map = new HashMap<>( count2 );
            for ( ; count2 > 0 ; count2-- ) {
                deserializeMapEntry(map,serializer);
            }
            result.artifactIgnores.put( key , map );
        }
        return result;
    }
    
    public boolean isAllVersionsBlacklisted(String groupId,String artifactId) {
    	if ( containsNeverMatcher(globalIgnores) ) {
    		return true;
    	}
    	if ( containsNeverMatcher( groupIdIgnores.get( groupId ) ) ) {
    		return true;
    	}
    	Map<String, List<VersionStringMatcher>> map1 = artifactIgnores.get( groupId );
    	return map1 != null && containsNeverMatcher( map1.get( artifactId ) );
    }
    
    private static boolean containsNeverMatcher(List<VersionStringMatcher> list) {
    	return list != null && list.contains( NEVER_MATCHER );
    }
    
    private static final NeverMatcher NEVER_MATCHER = new NeverMatcher();
    
    public static final class NeverMatcher extends VersionStringMatcher {

        public NeverMatcher() {
            super(".*", VersionMatcher.REGEX);
        }
        
        public boolean isIgnoredVersion(String s) {
            return true;
        }
    }
    
    /**
     * Matcher used to decide whether some artifact version should be ignored while checking for updates. 
     *
     * @author tobias.gierke@code-sourcery.de
     */
    public static class VersionStringMatcher 
    {
        public final String pattern;
        public final VersionMatcher type;
        public transient volatile Pattern compiledPattern;
        
        @JsonCreator    
        public static VersionStringMatcher createMatcher(@JsonProperty("pattern") String pattern, @JsonProperty("type") VersionMatcher type) 
        {
            Validate.notBlank( pattern , "pattern must not be NULL or blank");
            Validate.notNull(type,"type must not be NULL");
            
            if ( VersionMatcher.REGEX.equals( type ) && ".*".equals( pattern ) ) {
                return NEVER_MATCHER;
            }
            return new VersionStringMatcher(pattern,type);
        }
        
        @Override
        public boolean equals(Object other) 
        {
        	if ( other != null && getClass() == other.getClass() ) {
        		VersionStringMatcher o = (VersionStringMatcher) other; 
        		return Objects.equals(this.pattern, o.pattern) &&
        				Objects.equals( this.type, o.type );
        	}
        	return false;
        }
        
        public void serialize(BinarySerializer serializer) throws IOException  {
            serializer.writeString( type.text );
            serializer.writeString( pattern );
        }
        
        public static VersionStringMatcher deserialize(BinarySerializer serializer) throws IOException  
        {
            final String type = serializer.readString();
            final String pattern = serializer.readString();
            return VersionStringMatcher.createMatcher( pattern, VersionMatcher.fromString( type ) );
        }
        
        private VersionStringMatcher(String pattern, VersionMatcher type) {
            Validate.notBlank(pattern,"pattern must not be NULL or blank");
            Validate.notNull(type,"type must not be NULL");
            this.pattern = pattern;
            this.type = type;
            if ( type == VersionMatcher.REGEX ) {
                @SuppressWarnings("unused") // validate pattern has valid syntax
                final Pattern tmp = Pattern.compile( pattern );
            } 
        }
        
        @Override
        public String toString() {
        	return "IgnoreVersion["+type+"] = '"+pattern+"'";
        }
        
        @Override
        public int hashCode() {
            return 31 * (31  + pattern.hashCode()) + type.hashCode();
        }

        public boolean isIgnoredVersion(String s) 
        {
            if ( s == null ) {
                return false;
            }
            if ( type == VersionMatcher.EXACT ) {
                return Objects.equals(s,pattern);
            }
            Pattern pat = compiledPattern;
            if ( pat  == null ) 
            {
                pat = Pattern.compile(pattern);
                compiledPattern = pat;
            }
            return pat.matcher( s ).matches();
        }
    }
    
    /**
     * Adds an ignored version pattern.
     * 
     * This pattern will ignore any artifact version that matches the pattern.
     * 
     * @param pattern
     * @param matcher
     */
    public void addIgnoredVersion(String pattern,VersionMatcher matcher) 
    {
       final VersionStringMatcher newMatcher = VersionStringMatcher.createMatcher(pattern,matcher);
       if ( ! globalIgnores.contains( newMatcher ) ) {
           this.globalIgnores.add( newMatcher );
       }
    }
    
    /**
     * Adds an ignored version pattern.
     *  
     * This pattern will ignore versions all artifacts that have a group ID equal to/starting with
     * the group ID passed to this method.
     *  
     * @param groupId groupId to ignore. Note that all sub-packages of this one will be ignored as well.
     * @param pattern
     * @param matcher
     */
    public void addIgnoredVersion(String groupId,String pattern,VersionMatcher matcher) 
    {
        Validate.notBlank( groupId , "groupId must not be NULL or blank");
        List<VersionStringMatcher> existing = groupIdIgnores.computeIfAbsent( groupId, k -> new ArrayList<>() );
        VersionStringMatcher newMatcher = VersionStringMatcher.createMatcher(pattern,matcher);
        if ( ! existing.contains( newMatcher ) ) {
            existing.add( newMatcher);
        }
    }
    
    /**
     *  Adds an ignored version pattern.
     *       
     * This pattern will only ignore versions of artifacts with a matching artifact ID and group ID.
     *      
     * @param groupId
     * @param artifactId
     * @param pattern
     * @param matcher
     */
    public void addIgnoredVersion(String groupId,String artifactId,String pattern,VersionMatcher matcher) {
        Validate.notBlank( groupId , "groupId must not be NULL or blank");
        Validate.notBlank( artifactId , "artifactId must not be NULL or blank");

        Map<String, List<VersionStringMatcher>> existing = artifactIgnores.computeIfAbsent( groupId, k -> new HashMap<>() );
        List<VersionStringMatcher> existingSet = existing.computeIfAbsent( artifactId, k -> new ArrayList<>() );
        final VersionStringMatcher newMatcher = VersionStringMatcher.createMatcher(pattern,matcher );
        if ( ! existingSet.contains( newMatcher ) ) {
            existingSet.add( newMatcher );
        }
    }
    
    @Override
    public boolean isArtifactBlacklisted(Artifact artifact) 
    {
        Validate.notBlank( artifact.groupId , "group ID must not be NULL or blank");
        Validate.notBlank( artifact.artifactId , "artifact ID must not be NULL or blank");        
        Validate.notBlank( artifact.version , "artifact version must not be NULL or blank");
        
        final String version = artifact.version;
        if ( StringUtils.isNotBlank( version ) ) 
        {
            for ( VersionStringMatcher m : globalIgnores ) {
                if ( m.isIgnoredVersion( version ) ) {
                    return true;
                }
            }

            final List<VersionStringMatcher> groupOnlyMatchers = getMatchersForGroupId( artifact.groupId );
            for ( VersionStringMatcher m : groupOnlyMatchers ) {
                if ( m.isIgnoredVersion( version ) ) {
                    return true;
                }
            }

            Map<String, List<VersionStringMatcher>> groupAndArtifact = artifactIgnores.get( artifact.groupId );
            if ( groupAndArtifact != null ) 
            {
                final List<VersionStringMatcher> matchers = groupAndArtifact.get( artifact.artifactId );
                for ( VersionStringMatcher m : matchers ) {
                    if ( m.isIgnoredVersion( version ) ) {
                        return true;
                    }
                }                   
            }
        }
        return false;
    }

    private List<VersionStringMatcher> getMatchersForGroupId(String artifactGroupId)
    {
        String mostSpecificMatch = null;
        for ( String expectedGroupId : groupIdIgnores.keySet() )
        {
            // matching on group IDs is special as those rules actually apply to
            // ANY artifact that the same group ID _OR_ has a group ID that is a
            // CHILD of the group ID (so a group ID of "com.foo" is treated like "com.foo.*").
            // See https://www.mojohaus.org/versions-maven-plugin/version-rules.html where it says:
            // "Note: the groupId attribute in the rule elements has a lazy .* at the end, such that com.mycompany will match com.mycompany, com.mycompany.foo, com.mycompany.foo.bar, etc."

            final boolean matches = artifactGroupId.equals( expectedGroupId ) ||artifactGroupId.startsWith( expectedGroupId + "." );
            // we'll only keep the most-specific match (longest prefix)
            if ( matches && ( mostSpecificMatch == null || expectedGroupId.length() > mostSpecificMatch.length() ) )
            {
                mostSpecificMatch = expectedGroupId;
            }
        }
        return mostSpecificMatch == null ? Collections.emptyList() : groupIdIgnores.get( mostSpecificMatch );
    }

    @Override
    public boolean isVersionBlacklisted(String groupId, String artifactId, String version) 
    {
        Validate.notBlank( groupId , "groupId must not be NULL or blank");
        Validate.notBlank( artifactId , "artifactId must not be NULL or blank");
        Validate.notBlank( version , "version must not be NULL or blank");
        for ( VersionStringMatcher m : globalIgnores ) {
            if ( m.isIgnoredVersion(version) ) {
                return true;
            }
        }
        List<VersionStringMatcher> list = getMatchersForGroupId( groupId );
        for ( VersionStringMatcher m : list ) {
            if ( m.isIgnoredVersion( version ) ) {
                return true;
            }
        }

        final Map<String, List<VersionStringMatcher>> map = artifactIgnores.get( groupId );
        if ( map != null ) {
            list = map.get( artifactId );
            if ( list != null ) {
                for ( VersionStringMatcher m : list ) {
                    if ( m.isIgnoredVersion( version )) {
                        return true;
                    }
                }
            }
        }
        return false;
    }   
}