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
        for ( int i = 0,len = data.length ; i < len ; i++ ) 
        {
            final byte value = data[i];
            final int hi = (value & 0xf0) >> 4;
            final int lo = (value & 0x0f);
            buffer.append( nibbleToChar(hi) );
            buffer.append( nibbleToChar(lo) );
        }
        return buffer.toString();
    }
    
    private static char nibbleToChar(int nibble) {
        switch(nibble) {
            case 0 : return '0';
            case 1 : return '1';
            case 2 : return '2';
            case 3 : return '3';
            case 4 : return '4';
            case 5 : return '5';
            case 6 : return '6';
            case 7 : return '7';
            case 8 : return '8';
            case 9 : return '9';
            case 0x0a : return 'a';
            case 0x0b : return 'b';
            case 0x0c : return 'c';
            case 0x0d: return 'd';
            case 0x0e: return 'e';
            case 0x0f: return 'f';
            default:
                throw new IllegalArgumentException("Value out of range: "+nibble);
        }
    }
}
