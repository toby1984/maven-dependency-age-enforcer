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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinarySerializerTest {

    @Test
    void testSerDeser() throws IOException {

        final ByteArrayOutputStream bout = new ByteArrayOutputStream();
        final BinarySerializer.OutBuffer out = new BinarySerializer.OutBuffer( bout );

        final ZonedDateTime now = ZonedDateTime.now().truncatedTo( ChronoUnit.MILLIS ).withZoneSameInstant( ZoneId.of("UTC") );

        try( BinarySerializer ser = new BinarySerializer( out ) ) {
            ser.writeString( "test" );
            ser.writeInt( 0xdeadbeef );
            ser.writeBoolean( true );
            ser.writeByte( (byte) 123 );
            ser.writeBoolean( false );
            ser.writeShort( (short) 0xbeef );
            ser.writeArray( new byte[] {0x01, 0x02, 0x03} );
            ser.writeLong( 0x12345678 );

            ser.writeZonedDateTime( now );
            ser.writeBytes( new byte[] {0x02, 0x03, 0x04, 0x05} );
        }

        try( BinarySerializer ser = new BinarySerializer( new BinarySerializer.InBuffer(new ByteArrayInputStream( bout.toByteArray() ) ) ) ) {
            assertEquals( "test", ser.readString() );
            assertEquals( 0xdeadbeef,  ser.readInt() );
            assertTrue( ser.readBoolean() );
            assertEquals( (byte) 123, ser.readByte() );
            assertFalse( ser.readBoolean() );

            assertEquals( (short) 0xbeef, ser.readShort() );
            final byte[] array = ser.readArray();
            assertArrayEquals( new byte[] {0x01, 0x02, 0x03}, array );
            assertEquals( 0x12345678L, ser.readLong() );
            assertEquals( now, ser.readZonedDateTime() );
            final byte[] desired = new byte[4];
            ser.readBytes( desired );
            assertTrue( ser.isEOF() );
        }
    }
}