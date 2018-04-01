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

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.Logger;

/**
 * A very simple serializer that knows how to convert some basic Java types into a byte stream and back.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class BinarySerializer implements AutoCloseable,Closeable
{
    protected static final Logger LOG = org.apache.logging.log4j.LogManager.getLogger(BinarySerializer.class);
    
    private final IBuffer buffer;
    
    public interface IBuffer extends AutoCloseable,Closeable
    {
        public byte read() throws IOException;

        public boolean isEOF() throws IOException;
        
        public void write(byte value) throws IOException;
        
        @Override
        public void close() throws IOException;
        
        public static IBuffer wrap(byte[] data) throws IOException {
            return wrap( new ByteArrayInputStream(data) );
        }
        
        public static IBuffer wrap(InputStream in) throws IOException 
        {
            return new InBuffer( in );
        }
        
        public static IBuffer wrap(OutputStream out) throws IOException 
        {
            return new OutBuffer(out);
        }        
        
        public int currentOffset();
    }
    
    protected static final class InBuffer implements IBuffer
    {
        private final InputStream in;
        private int next;
        public int offset;
        
        public InBuffer(InputStream in) throws IOException 
        {
            Validate.notNull(in, "in must not be NULL");
            this.in = in;
            this.next = in.read();
        }
        
        @Override
        public byte read() throws IOException 
        {
            int result = this.next;
            if ( result == -1 ) {
                throw new EOFException();
            }
            offset++;
            this.next = in.read();
            if ( LOG.isTraceEnabled() ) {
                LOG.trace("read(): "+asHex(offset,8)+" => "+asHex( result & 0xff, 2 ) );
            }
            return (byte) result;
        }
        
        private String asHex(int value,int padLen) {
            String s = Integer.toHexString( value );
            s = StringUtils.leftPad(s,padLen,'0');
            return "0x"+s;
        }

        @Override public boolean isEOF() throws IOException { return next == -1; }

        @Override public void write(byte value) throws IOException { throw new UnsupportedOperationException("not supported: write()"); }

        @Override public void close() throws IOException { in.close(); }
        
        @Override
        public String toString()
        {
            return "InBuffer @ 0x"+Integer.toHexString( offset );
        }

        @Override
        public int currentOffset()
        {
            return offset;
        }
    }
    
    protected static final class OutBuffer implements IBuffer 
    {
        private final OutputStream out;
        public int offset;

        public OutBuffer(OutputStream out) {
            Validate.notNull(out, "out must not be NULL");
            this.out = out;
        }
        
        @Override
        public byte read() throws IOException {
            throw new UnsupportedOperationException("method not supported: read()");
        }

        @Override
        public boolean isEOF() throws IOException {
            throw new UnsupportedOperationException("method not supported: isEOF()");
        }

        @Override
        public void write(byte value) throws IOException {
            out.write( value );
            offset++;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
        
        @Override
        public String toString()
        {
            return "OutBuffer @ 0x"+Integer.toHexString( offset );
        }

        @Override
        public int currentOffset()
        {
            return offset;
        }        
    }
    
    public BinarySerializer(IBuffer buffer) 
    {
        this.buffer = buffer;
    }

    public boolean isEOF() throws IOException 
    {
        return buffer.isEOF();
    }
    
    @Override    
    public void close() throws IOException {
        this.buffer.close();
    }
    
    public void writeArray(byte[] value) throws IOException 
    {
        writeInt( value.length );
        for ( int i = 0,len=value.length ; i< len ; i++ ) {
            writeByte( value[i] );
        }
    }

    public byte[] readArray() throws IOException {
        final int len = readInt();
        final byte[] result = new byte[ len ];
        for ( int i = 0 ; i < len ; i++ ) {
            result[i] = readByte();
        }
        return result;
    }
    
    public int currentOffset() {
        return buffer.currentOffset();
    }

    public void writeByte(byte value) throws IOException {
        buffer.write( (byte) (value & 0xff ));
    }

    public byte readByte() throws IOException {
        return buffer.read();
    }
    
    public void readBytes(byte[] destination,int count) throws IOException 
    {
        for ( int i = 0 ; i < count ; i++ ) 
        {
            destination[i] = readByte();
        }
    }    
    
    public void writeBytes(byte[] src,int count) throws IOException 
    {
        for ( int i = 0 ; i < count ; i++ ) 
        {
            writeByte( src[i] );
        }
    }

    public void writeBoolean(boolean value) throws IOException {
        writeByte( (byte) (value ? 0x12 : 0x34 ) );
    }

    public boolean readBoolean() throws IOException 
    {
        final byte v = readByte();
        if ( v == (byte) 0x12 ) {
            return true;
        }
        if ( v == (byte) 0x34 ) {
            return false;
        }
        throw new IOException("Expected a boolean value but got 0x"+Integer.toHexString( v & 0xff ) );
    }

    public void writeShort(short value) throws IOException {
        writeByte( (byte) ((value >> 8) & 0xff));
        writeByte( (byte) (value & 0xff));
    }

    public short readShort() throws IOException {
        int hi= (( (int) readByte()<<8) & 0xff00); 
        int lo = (int) readByte() & 0xff;
        return (short) (hi | lo);
    }

    public void writeInt(int value) throws IOException {
        writeShort( (short) ((value >> 16 ) & 0xffff) );
        writeShort( (short) (value & 0xffff) );
    }

    public int readInt() throws IOException {
        int hi = ((int)readShort() << 16) & 0xffff0000;
        int lo = (int) readShort() & 0xffff;
        return hi|lo;
    }        

    public void writeLong(long value) throws IOException {
        writeInt( (int) ( (value >> 32) & 0xffffffff) );
        writeInt( (int) ( value & 0xffffffff) );
    }

    public long readLong() throws IOException {
        long hi = (long) readInt() <<32;
        long lo =(long) readInt() & 0xffffffffL;
        return hi|lo;
    }
    
    public void writeDouble(double value) throws IOException {
        writeLong( Double.doubleToRawLongBits( value ) );
    }
    
    public double readDouble() throws IOException {
        return Double.longBitsToDouble( readLong() );
    }

    public void writeString(String s) throws IOException {
        if ( s == null ) {
            writeByte( (byte) 0 );
            return;
        }
        writeByte( (byte) 1 );
        writeArray( s.getBytes("UTF8") );
    }

    public String readString() throws IOException 
    {
        if ( readByte() == 0 ) {
            return null;
        }
        return new String( readArray() , "UTF8" );
    }        
    
    public void writeZonedDateTime(ZonedDateTime dt) throws IOException 
    {
        if ( dt == null ) {
            writeBoolean(false);
        } else {
            writeBoolean(true);
            writeString( dt.getZone().getId() );
            final long millis = dt.toEpochSecond()*1000 + dt.getNano()/1000000;
            writeLong( millis );
        }
    }
    
    public ZonedDateTime readZonedDateTime() throws IOException {
        boolean isPresent = readBoolean();
        if ( ! isPresent ) {
            return null;
        }
        final ZoneId id = ZoneId.of( readString() );
        final Instant instant = Instant.ofEpochMilli( readLong() );
        return ZonedDateTime.ofInstant(instant,id);
    }

    public void writeDate(Date d) throws IOException 
    {
        if ( d == null ) {
            writeByte( (byte) 0 );
            return;
        }
        writeByte( (byte) 1 );
        writeLong( d.getTime() );
    }

    public Date readDate() throws IOException 
    {
        if ( readByte() == 0 ) {
            return null;
        }
        return new Date( readLong() );
    }
}