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

class StorageSerializationFormatTest
{
    @Test
    void testLessThan() {
        assertTrue(  StorageSerializationFormat.V1.isBefore( StorageSerializationFormat.V2 ) );
        assertFalse( StorageSerializationFormat.V1.isBefore( StorageSerializationFormat.V1 ) );
        assertFalse( StorageSerializationFormat.V2.isBefore( StorageSerializationFormat.V1 ) );
    }

    @Test
    void testGreaterThan() {
        assertTrue(  StorageSerializationFormat.V2.isAtLeast( StorageSerializationFormat.V1 ) );
        assertTrue(  StorageSerializationFormat.V2.isAtLeast( StorageSerializationFormat.V2 ) );
        assertFalse( StorageSerializationFormat.V2.isAtLeast( StorageSerializationFormat.V3 ) );
    }
}