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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Helper class to track requests per hour/requests per last 24 hours.
 * @author tobias.gierke@code-sourcery.de
 */
public class RequestsPerHour {

    // @GuardedBy( counts )
    private final Map<Integer, Integer> counts = new TreeMap<>();

    // @GuardedBy( counts )
    private long mostRecentAccess;

    public RequestsPerHour() {
    }

    public RequestsPerHour(RequestsPerHour other) {
        this.counts.putAll( other.counts );
        this.mostRecentAccess = other.mostRecentAccess;
    }

    public RequestsPerHour createCopy() {
        synchronized ( counts ) {
            return new RequestsPerHour( this );
        }
    }

    public void update() {
        update(1);
    }

    public void update(int count) {
        if ( count < 0 ) {
            throw new IllegalArgumentException( "count needs to be >= 0 " );
        }
        synchronized ( counts ) {
            mostRecentAccess = System.currentTimeMillis();
            Integer hour = currentHour();
            counts.compute( hour, (key, existing) -> existing == null ? count : existing + count );
        }
    }

    public Optional<ZonedDateTime> getMostRecentAccess() {
        synchronized ( counts ) {
            return Optional.ofNullable(
                mostRecentAccess == 0 ? null : ZonedDateTime.ofInstant( Instant.ofEpochMilli( mostRecentAccess ), ZoneId.systemDefault() ) );
        }
    }

    private int currentHour() {
        return ZonedDateTime.now().getHour();
    }

    public int getCountForCurrentHour() {
        return getCountForHour( currentHour() );
    }

    public int getCountForLast24Hours()
    {
        synchronized ( counts ) {
            int totalCount = 0;
            int currentHour = currentHour();
            for ( int i = 0; i < 24; i++ ) {
                totalCount += getCountForHour( currentHour );
                currentHour -= 1;
                if ( currentHour < 0 ) {
                    currentHour += 24;
                }
            }
            return totalCount;
        }
    }

    public int getCountForHour(int hour) {
        synchronized ( counts ) {
            final Integer count = counts.get( hour );
            return count == null ? 0 : count;
        }
    }
}
