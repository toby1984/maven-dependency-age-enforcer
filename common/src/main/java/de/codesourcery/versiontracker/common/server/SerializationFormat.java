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
    V2( (short) 2 );

    public final short version;

    SerializationFormat(short version) {
        this.version = version;
    }
}
