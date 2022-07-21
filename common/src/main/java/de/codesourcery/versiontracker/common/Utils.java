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

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    public static String toHex(byte[] data) 
    {
        final StringBuilder buffer = new StringBuilder( data.length*2 );
        for ( byte value : data ) {
            final int hi = (value >>> 4) & 0xf;
            final int lo = value & 0xf;
            buffer.append( HEX_CHARS[ hi ] );
            buffer.append( HEX_CHARS[ lo ] );
        }
        return buffer.toString();
    }
}
