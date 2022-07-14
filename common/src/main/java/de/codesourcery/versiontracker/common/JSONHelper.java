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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * JSON serialization helper.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class JSONHelper 
{
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneId.of("UTC"));
	
	public static APIRequest parseAPIRequest(String jsonString,ObjectMapper mapper) throws IOException 
	{
	    final JsonNode tree = mapper.readTree( jsonString );
	    final String cmd = tree.get( "command").asText();
	    final String clientVersion = tree.get( "clientVersion").asText();
		final APIRequest.Command command = APIRequest.Command.fromString(cmd);

		final APIRequest result = switch ( command ) {
			case QUERY -> mapper.readValue( jsonString, QueryRequest.class );
		};
		result.clientVersion = clientVersion;
		return result;
	}
	
	public static ObjectMapper newObjectMapper() 
	{
	    final ObjectMapper result = new ObjectMapper();
	    
	    final VisibilityChecker<?> checker = result.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.NONE);
        result.setVisibility(checker);	    
	    
	    final SimpleModule module = new SimpleModule();
	    module.addSerializer(ZonedDateTime.class,new ZonedDateTimeSerializer());
	    module.addDeserializer(ZonedDateTime.class, new ZonedDateTimeDeserializer());
	    result.registerModule(module);

	    result.setSerializationInclusion( Include.NON_NULL );
	    return result;
	}
	
	public static class ZonedDateTimeSerializer extends StdSerializer<ZonedDateTime> 
	{
	    private static final ZoneId UTC = ZoneId.of("UTC");

        public ZonedDateTimeSerializer() {
	        super(ZonedDateTime.class);
	    }
	   
	    @Override
	    public void serialize(ZonedDateTime value, JsonGenerator jgen, SerializerProvider provider) throws IOException
	    {
	        jgen.writeString( DATE_FORMATTER.format( value.withZoneSameInstant( UTC ) ) );
	    }
	}	
	
    public static class ZonedDateTimeDeserializer extends StdDeserializer<ZonedDateTime> 
    {
        public ZonedDateTimeDeserializer() {
            super(ZonedDateTime.class);
        }
       
        @Override
        public ZonedDateTime deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException
        {
            final JsonNode node = jp.getCodec().readTree(jp);
            final String text = node.textValue();
            return ZonedDateTime.parse( text, DATE_FORMATTER );
        }
    }   	
}