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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * A very simple serializer that knows how to convert some basic Java types into a byte stream and back.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class BinarySerializer implements AutoCloseable,Closeable
{
    protected static final Logger LOG = org.apache.logging.log4j.LogManager.getLogger(BinarySerializer.class);
    
    protected static final ZoneId UTC =  ZoneId.of( "UTC" );
    
    static final boolean TRACK_OFFSET = false;
    
    public final IBuffer buffer;
    
    public interface IBuffer extends Closeable
    {
        int maybeReadByte() throws IOException;
        
        byte read() throws IOException;
        
        void read(byte[] destination) throws IOException;

        boolean isEOF() throws IOException;
        
        void write(byte value) throws IOException;
        
        void write(byte[] data, int offset, int length) throws IOException;
        
        void write(byte[] array) throws IOException;

        void skip(int bytesToSkip) throws IOException;
        
        @Override
        void close() throws IOException;
        
        static IBuffer wrap(byte[] data) throws IOException {
            return wrap( new ByteArrayInputStream(data) );
        }
        
        static IBuffer wrap(InputStream in) throws IOException
        {
            return new InBuffer( in );
        }
        
        static IBuffer wrap(OutputStream out)
        {
            return new OutBuffer(out);
        }
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
        public void skip(int bytesToSkip) throws IOException {
            if ( bytesToSkip == 0 ) {
                return;
            }
            if ( bytesToSkip < 0 ) {
                throw new IllegalArgumentException( "A negative offset is not allowed : " + bytesToSkip );
            }
            if ( this.next == -1 ) {
                throw new EOFException( "Premature end of input, expected " + bytesToSkip + " more bytes" );
            }
            in.skipNBytes( bytesToSkip - 1 );
        }

        @Override
        public int maybeReadByte() throws IOException {
            int result = this.next;
            if ( result != -1 ) {
                if (TRACK_OFFSET) {
                    offset++;
                }
                this.next = in.read();
                if ( TRACK_OFFSET && LOG.isTraceEnabled() ) {
                    LOG.trace("read(): "+asHex(offset,8)+" => "+asHex( result & 0xff, 2 ) );
                }                
            }
            return result;
        }

        @Override
        public byte read() throws IOException 
        {
            int result = maybeReadByte();
            if ( result == -1 ) {
                throw new EOFException();
            }
            return (byte) result;
        }
        
        @Override
        public void read(byte[] destination) throws IOException 
        {
        	if ( destination.length > 1 ) 
        	{
                int result = this.next;
                if ( result == -1 ) {
                    throw new EOFException();
                }
        		destination[0] = (byte) result;
        		final int expected = destination.length-1;
				final int bytesRead = in.read(destination, 1, expected);
        		if ( bytesRead != expected ) {
        			throw new EOFException("Expected "+destination.length+" bytes but got only "+(1+bytesRead));
        		}
        		if ( TRACK_OFFSET ) {
        		    offset += destination.length;
        		}
        		this.next = in.read();
        	} else if ( destination.length == 1 ) {
        		destination[0] = read();
        	}
        }
        
        private String asHex(int value,int padLen) {
            String s = Integer.toHexString( value );
            s = StringUtils.leftPad(s,padLen,'0');
            return "0x"+s;
        }

        @Override public boolean isEOF() { return next == -1; }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            throw new UnsupportedOperationException( "not supported: write(byte[],int,int)");
        }

        @Override public void write(byte value) { throw new UnsupportedOperationException("not supported: write(byte)"); }
        
        @Override public void write(byte[] value) { throw new UnsupportedOperationException("not supported: write(byte[])"); }

        @Override public void close() throws IOException { in.close(); }
        
        @Override
        public String toString()
        {
            return "InBuffer @ 0x"+Integer.toHexString( offset );
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
        public void skip(int bytesToSkip) { throw new UnsupportedOperationException( "method not supported: skip(int)"); }

        @Override
        public int maybeReadByte() {throw new UnsupportedOperationException( "method not supported: maybeReadByte()");}

        @Override
        public byte read() {
            throw new UnsupportedOperationException("method not supported: read()");
        }
        
        @Override
        public void read(byte[] destination) {
        	throw new UnsupportedOperationException("method not supported: read(byte[])");
        }

        @Override
        public boolean isEOF() {
            throw new UnsupportedOperationException("method not supported: isEOF()");
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            out.write( data, offset, length );
            if ( TRACK_OFFSET ) {
                offset += length;
            }
        }

        @Override
        public void write(byte value) throws IOException {
            out.write( value );
            if ( TRACK_OFFSET ) {
                offset++;
            }
        }
        
        @Override
        public void write(byte[] array) throws IOException {
        	out.write( array );
        	if ( TRACK_OFFSET ) {
        	    offset += array.length;
        	}
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
        
        @Override
        public String toString()
        {
            return TRACK_OFFSET ? "OutBuffer @ 0x"+Integer.toHexString( offset ) : "OutBuffer";
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
        this.buffer.write( value );
    }

    public byte[] readArray() throws IOException {
        final byte[] result = new byte[ readInt() ];
        buffer.read(result);
        return result;
    }
    
    public void writeBytes(byte[] buffer) throws IOException {
        writeBytes( buffer, 0, buffer.length );
    }
    
    public void writeBytes(byte[] data, int offset, int length) throws IOException {
        buffer.write( data, offset ,length );
    }
    
    public void writeByte(byte value) throws IOException {
        buffer.write( (byte) (value & 0xff ));
    }

    public void readBytes(byte[] destination) throws IOException {
        buffer.read(destination);
    }

    public byte readByte() throws IOException {
        return buffer.read();
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
        writeInt( (int) ((value >> 32)) );
        writeInt( (int) (value) );
    }

    public long readLong() throws IOException {
        long hi = (long) readInt() <<32;
        long lo =(long) readInt() & 0xffffffffL;
        return hi|lo;
    }
    
    public void writeString(String s) throws IOException {
        if ( s == null ) {
            writeByte( (byte) 0 );
            return;
        }
        writeByte( (byte) 1 );
        writeArray( s.getBytes( StandardCharsets.UTF_8 ) );
    }

    public String readString() throws IOException 
    {
        if ( readByte() == 0 ) {
            return null;
        }
        return new String( readArray() , StandardCharsets.UTF_8 );
    }        
    
    public void writeZonedDateTime(ZonedDateTime dt) throws IOException 
    {
        if ( dt == null ) {
            writeBoolean(false);
        } else {
            writeBoolean(true);
            final long millis = dt.toEpochSecond()*1000 + dt.getNano()/1000000;
            writeLong( millis );
        }
    }
    
    public ZonedDateTime readZonedDateTime() throws IOException {
        boolean isPresent = readBoolean();
        if ( ! isPresent ) {
            return null;
        }
        final Instant instant = Instant.ofEpochMilli( readLong() );
        return ZonedDateTime.ofInstant(instant,UTC);
    }
}