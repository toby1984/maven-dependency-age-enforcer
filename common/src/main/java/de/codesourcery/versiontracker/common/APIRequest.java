/**
 * Copyright 2015 Tobias Gierke <tobias.gierke@code-sourcery.de>
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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Abstract base-class for all API requests.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public abstract class APIRequest 
{
    /**
     * The API command to perform.
     *
     * @author tobias.gierke@code-sourcery.de
     */
	public static enum Command 
	{
	    /**
	     * 
	     */
	    @JsonProperty("query")
		QUERY("query");
		
	    private final String text;
	    
		private Command(String text) {
			this.text = text;
		}
		
		public static Command fromString(String s)
		{
			for ( Command v : values() ) {
				if ( v.text.equals(s) ) {
					return v;
				}
			}
			throw new IllegalArgumentException("Unknown command '"+s+"'");
		}		
	}
	
	public String clientVersion;
	public final Command command;
	
	public APIRequest(APIRequest.Command cmd) {
		this.command = cmd;
	}
}
