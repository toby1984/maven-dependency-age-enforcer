package de.codesourcery.versiontracker.common.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SerializationFormatTest
{
    @Test
    void testLessThan() {
        assertTrue(  SerializationFormat.V1.isBefore( SerializationFormat.V2 ) );
        assertFalse( SerializationFormat.V1.isBefore( SerializationFormat.V1 ) );
        assertFalse( SerializationFormat.V2.isBefore( SerializationFormat.V1 ) );
    }

    @Test
    void testGreaterThan() {
        assertTrue(  SerializationFormat.V2.isAtLeast( SerializationFormat.V1 ) );
        assertTrue(  SerializationFormat.V2.isAtLeast( SerializationFormat.V2 ) );
        assertFalse( SerializationFormat.V2.isAtLeast( SerializationFormat.V3 ) );
    }
}