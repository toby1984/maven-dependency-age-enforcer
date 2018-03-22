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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Blacklist implements IBlacklistCheck 
{
    public static enum VersionMatcher 
    {
        @JsonProperty("exact")        
        EXACT,
        @JsonProperty("regex")
        REGEX;
        
        public static VersionMatcher fromString(String s) 
        {
            switch( s.toLowerCase() ) 
            {
                case "exact":  return VersionMatcher.EXACT;
                case "regex":  return VersionMatcher.REGEX;
                default:
                    throw new IllegalArgumentException("Unsupported version matcher type: '"+s+"'");
            }
        }
    }
    
    private final List<VersionStringMatcher> globalIgnores = new ArrayList<>();
    private final Map<String,List<VersionStringMatcher>>  groupIdIgnores = new HashMap<>();
    private final Map<String,Map<String,List<VersionStringMatcher>>>  artifactIgnores = new HashMap<>();
    
    public boolean isAllVersionsBlacklisted(String groupId,String artifactId) {
    	if ( containsNeverMatcher(globalIgnores ) ) {
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
        
        private VersionStringMatcher(String pattern, VersionMatcher type) {
            Validate.notBlank(pattern,"pattern must not be NULL or blank");
            Validate.notNull(type,"type must not be NULL");
            this.pattern = pattern;
            this.type = type;
            if ( type == VersionMatcher.REGEX ) {
                Pattern.compile(pattern);
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

        @Override
        public boolean equals(Object obj) 
        {
            if ( this == obj ) {
                return true;
            }            
            if ( obj instanceof VersionStringMatcher) {
                final VersionStringMatcher other = (VersionStringMatcher) obj;
                return this.pattern.equals( other.pattern ) && this.type == other.type;
            }
            return false;
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
     * This pattern will only ignore versions of artifacts with a matching group ID.
     *  
     * @param groupId
     * @param pattern
     * @param matcher
     */
    public void addIgnoredVersion(String groupId,String pattern,VersionMatcher matcher) 
    {
        Validate.notBlank( groupId , "groupId must not be NULL or blank");
        List<VersionStringMatcher> existing = groupIdIgnores.get( groupId );
        if ( existing == null ) {
            existing = new ArrayList<>();
            groupIdIgnores.put( groupId, existing );
        }
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

        Map<String, List<VersionStringMatcher>> existing = artifactIgnores.get( groupId );
        if ( existing == null ) {
            existing = new HashMap<>();
            artifactIgnores.put( groupId, existing );
        }
        List<VersionStringMatcher> existingSet = existing.get(artifactId);
        if ( existingSet == null ) {
            existingSet = new ArrayList<>();
            existing.put( artifactId, existingSet );
        }
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
            
            final List<VersionStringMatcher> groupOnlyMatchers = groupIdIgnores.get( artifact.groupId );
            if ( groupOnlyMatchers != null ) 
            {
                for ( VersionStringMatcher m : groupOnlyMatchers ) {
                    if ( m.isIgnoredVersion( version ) ) {
                        return true;
                    }
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
        List<VersionStringMatcher> list = groupIdIgnores.get( groupId );
        if ( list != null ) {
            for ( VersionStringMatcher m : list ) {
                if ( m.isIgnoredVersion( version ) ) {
                    return true;
                }
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