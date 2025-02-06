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
package de.codesourcery.versiontracker.common.server;

import java.util.HashMap;
import java.util.Map;

/**
 * Serialization format.
 * @author tobias.gierke@code-sourcery.de
 */
public enum SerializationFormat
{
    /*
     * Initial format.
     */
    V1( (short) 1 ),
    /**
     * New field:
     * {@link de.codesourcery.versiontracker.common.Version#releaseDate}
     */
    V2( (short) 2 ),
    /**
     * New field:
     *  {@link de.codesourcery.versiontracker.common.Version#firstSeenByServer}
     */
    V3( (short) 3 ),
    ;

    public final short version;

    // hack to work around JVM rules...
    private static final class Holder {
        public final static Map<Short,SerializationFormat> versionsByNumber = new HashMap<>();
    }

    SerializationFormat(short version) {
        this.version = version;
        if ( Holder.versionsByNumber.put(version, this) != null ) {
            throw new IllegalStateException("Duplicate version number: " + version);
        }
    }

    @Override
    public String toString()
    {
        return "serialization format V"+version;
    }

    public boolean isBefore(SerializationFormat other) {
        return this.version < other.version;
    }

    /**
     * Returns whether this version is equal to/comes after a given version version.
     *
     * @param other
     * @return
     */
    public boolean isAtLeast(SerializationFormat other)
    {
        return this.version >= other.version;
    }

    public static SerializationFormat fromVersionNumber(short number) {
        final SerializationFormat result = Holder.versionsByNumber.get(number);
        if ( result == null ) {
            throw new IllegalArgumentException("Unknown serialization format version number: "+number);
        }
        return result;
    }

    public static SerializationFormat latest()
    {
        SerializationFormat latest = null;
        for ( final SerializationFormat v : values() )
        {
            if ( latest == null || v.version > latest.version ) {
                latest = v;
            }
        }
        return latest;
    }
}