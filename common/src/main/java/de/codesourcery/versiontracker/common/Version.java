/**
 * Copyright 2015 Tobias Gierke <tobias.gierke@code-sourcery.de>
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
    
    public Version(String versionString, ZonedDateTime releaseDate)
    {
        this.versionString = versionString;
        this.releaseDate = releaseDate;
    }

    public Version(Version other)
    {
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
        return v instanceof Version && Objects.equals( this.versionString , ((Version) v).versionString );
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
