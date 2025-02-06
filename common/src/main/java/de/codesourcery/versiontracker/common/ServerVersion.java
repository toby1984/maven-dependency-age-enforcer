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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import de.codesourcery.versiontracker.common.server.SerializationFormat;

public enum ServerVersion
{
    V1( "1.0", (short) 1, SerializationFormat.V1 ),
    V2( "2.0", (short) 2, SerializationFormat.V3 ),
    ;

    public String versionString;
    public final short version;
    public final SerializationFormat serializationFormat;

    // hack to work around JVM rules...
    private static final class Holder
    {
        public final static Map<Short, ServerVersion> versionsByNumber = new HashMap<>();
    }

    ServerVersion(String versionString, short version, SerializationFormat serializationFormat)
    {
        this.versionString = versionString;
        this.version = version;
        this.serializationFormat = serializationFormat;
        if ( ServerVersion.Holder.versionsByNumber.put( version, this ) != null )
        {
            throw new IllegalStateException( "Duplicate version number: " + version );
        }
    }

    @Override
    public String toString()
    {
        return "client V" + version;
    }

    public boolean isBefore(ClientVersion other)
    {
        return this.version < other.version;
    }

    /**
     * Returns whether this version is equal to/comes after a given version version.
     *
     * @param other
     * @return
     */
    public boolean isAtLeast(ClientVersion other)
    {
        return this.version >= other.version;
    }

    public static ServerVersion fromVersionNumber(String versionString) {
        return Arrays.stream( ServerVersion.values() ).filter( x -> x.versionString.equals( versionString ) )
            .findFirst()
            .orElseThrow( () -> new IllegalArgumentException("Unknown client version: '"+versionString+"'") );
    }

    public static ServerVersion latest()
    {
        ServerVersion latest = null;
        for ( final ServerVersion v : values() )
        {
            if ( latest == null || v.version > latest.version )
            {
                latest = v;
            }
        }
        return latest;
    }
}