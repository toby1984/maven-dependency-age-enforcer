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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class APISerializationFormatTest
{
    @Test
    void testLessThan() {
        assertTrue(  APISerializationFormat.V1.isBefore( APISerializationFormat.V2 ) );
        assertFalse( APISerializationFormat.V1.isBefore( APISerializationFormat.V1 ) );
        assertFalse( APISerializationFormat.V2.isBefore( APISerializationFormat.V1 ) );
    }

    @Test
    void testGreaterThan() {
        assertTrue(  APISerializationFormat.V2.isAtLeast( APISerializationFormat.V1 ) );
        assertTrue(  APISerializationFormat.V2.isAtLeast( APISerializationFormat.V2 ) );
        assertFalse( APISerializationFormat.V2.isAtLeast( APISerializationFormat.V3 ) );
    }
}