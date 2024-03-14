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

import de.codesourcery.versiontracker.common.server.SerializationFormat;
import org.apache.commons.lang3.Validate;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * A version with the associated upload/release date.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class Version
{
    public String versionString;
    public ZonedDateTime releaseDate;

    public Version() {
    }

    public Version(String version) {
        Validate.notBlank( version, "version must not be null or blank");
        this.versionString = version;
    }

    public static Version of(String s) {
        return new Version( s );
    }

    public void serialize(BinarySerializer serializer, SerializationFormat fileFormat) throws IOException
    {
        serializer.writeString( versionString );
        serializer.writeZonedDateTime(releaseDate);
    }

    public static Version deserialize(BinarySerializer serializer, SerializationFormat fileFormatVersion) throws IOException {
        final Version result = new Version();
        result.versionString = serializer.readString();
        result.releaseDate = serializer.readZonedDateTime();
        return result;
    }

    public static boolean sameFields(Version a,Version b)
    {
    	if ( a == null ||b == null ) {
    		return a == b;
    	}
    	if ( ! Objects.equals( a.versionString, b.versionString ) ) {
    		return false;
    	}
    	if ( a.releaseDate == null || b.releaseDate == null ) {
    		return a.releaseDate == b.releaseDate;
    	}
    	return a.releaseDate.toInstant().equals( b.releaseDate.toInstant() );
    }
    
    public Version(String versionString, ZonedDateTime releaseDate)
    {
        Validate.notBlank( versionString, "versionString must not be null or blank");
        this.versionString = versionString;
        this.releaseDate = releaseDate;
    }

    public Version(Version other)
    {
        Validate.notNull( other, "other must not be null" );
        this.versionString = other.versionString;
        this.releaseDate = other.releaseDate;
    }
    
    public boolean hasReleaseDate() {
        return releaseDate != null;
    }
    
    @Override
    public boolean equals(Object v) {
        if ( this == v ) {
            return true;
        }
        return v instanceof Version ver && Objects.equals( this.versionString , ver.versionString );
    }
    
    @Override
    public int hashCode() {
        return this.versionString == null ? 0 : this.versionString.hashCode();
    }
    
    public Version copy() {
        return new Version(this);
    }

    @Override
    public String toString()
    {
        return "version '"+ versionString + "', " + releaseDate;
    }
}
