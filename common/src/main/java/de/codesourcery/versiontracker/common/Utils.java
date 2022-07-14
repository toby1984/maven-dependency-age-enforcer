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

public class Utils {

    public static String toHex(byte[] data) 
    {
        final StringBuilder buffer = new StringBuilder( data.length*2 );
        for ( final byte value : data ) {
            final int hi = (value & 0xf0) >> 4;
            final int lo = (value & 0x0f);
            buffer.append( nibbleToChar( hi ) );
            buffer.append( nibbleToChar( lo ) );
        }
        return buffer.toString();
    }
    
    private static char nibbleToChar(int nibble) {
        return switch ( nibble ) {
            case 0 -> '0';
            case 1 -> '1';
            case 2 -> '2';
            case 3 -> '3';
            case 4 -> '4';
            case 5 -> '5';
            case 6 -> '6';
            case 7 -> '7';
            case 8 -> '8';
            case 9 -> '9';
            case 0x0a -> 'a';
            case 0x0b -> 'b';
            case 0x0c -> 'c';
            case 0x0d -> 'd';
            case 0x0e -> 'e';
            case 0x0f -> 'f';
            default -> throw new IllegalArgumentException( "Value out of range: " + nibble );
        };
    }
}
