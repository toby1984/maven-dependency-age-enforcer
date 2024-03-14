package de.codesourcery.versiontracker.common.server;

public enum TaggedRecordType {
    VERSION_DATA( 0x01 ),
    END_OF_FILE( 0xff );
    final byte tag;

    TaggedRecordType(int tag) {
        this.tag = (byte) tag;
    }
}
