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
     * {@link de.codesourcery.versiontracker.common.Version#releaseDateRequested}
     */
    V2( (short) 2 );

    public final short version;

    SerializationFormat(short version) {
        this.version = version;
    }
}
