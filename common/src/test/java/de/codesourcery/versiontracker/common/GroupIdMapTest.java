package de.codesourcery.versiontracker.common;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GroupIdMapTest
{
    private GroupIdMap<String> map;

    @BeforeEach
    public void setup() {
        map = new GroupIdMap<>();
    }


    @Test
    void testPutNoWildcards()
    {
        assertTrue(  map.put( "com.voipfuture", "1" ) );
        assertTrue(  map.put( "com.voipfuture.test", "2" ) );
        assertFalse( map.put( "com.voipfuture.test", "2" ) );

        assertListEquals( List.of("1"), map.get( "com.voipfuture" ) );
        assertListEquals( List.of("2"), map.get( "com.voipfuture.test" ) );
        assertListEquals( List.of(), map.get( "com.test" ) );
    }

    @Test
    void testPutInvalidWildcards()
    {
        assertThrows( IllegalArgumentException.class, () -> map.put( "", "1" ) );
        assertThrows( NullPointerException.class, () -> map.put( null, "1" ) );
        assertThrows( IllegalArgumentException.class, () -> map.put( ".*", "1" ) );
        assertThrows( IllegalArgumentException.class, () -> map.put( "..*", "1" ) );
        assertThrows( IllegalArgumentException.class, () -> map.put( "test..*", "1" ) );
        assertThrows( IllegalArgumentException.class, () -> map.put( "test.*.something", "1" ) );
    }

    @Test
    void testPutWildcards()
    {
        assertTrue(  map.put( "com.voipfuture.*", "1" ) );

        assertListEquals( List.of("1"), map.get( "com.voipfuture" ) );
        assertListEquals( List.of("1"), map.get( "com.voipfuture.test" ) );
        assertListEquals( List.of(), map.get( "com.something" ) );
        assertListEquals( List.of(), map.get( "com" ) );
    }

    private static <T> void assertListEquals(List<T> expected, List<T> actual) {
        if ( expected.size() != actual.size() ) {
            fail( "Sets are of different size. Expected = " + expected + ", got = " + actual );
        }
        for ( final T exp : expected )
        {
            if ( ! actual.contains(exp) ) {
                fail("Expected element '" + exp + "' not found in "+actual);
            }
        }
    }
}