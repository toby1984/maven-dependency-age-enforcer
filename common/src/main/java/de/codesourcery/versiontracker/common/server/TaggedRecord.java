package de.codesourcery.versiontracker.common.server;

public class TaggedRecord {

    public enum RecordType
    {
        VERSION_DATA(0x01),
        END_OF_FILE(0xff);
        final byte tag;

        RecordType(int tag) {
            this.tag = (byte) tag;
        }
    }

    public RecordType type;
    public int payloadSize;
    public byte[] payload;
}
