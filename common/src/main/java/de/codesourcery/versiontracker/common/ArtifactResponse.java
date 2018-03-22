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

/**
 * Response for a single artifact from a {@link QueryRequest}.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class ArtifactResponse 
{
    /**
     * Status that indicates whether a newer version of the given artifact is available.
     *
     * @author tobias.gierke@code-sourcery.de
     */
	public static enum UpdateAvailable 
	{
	    /**
	     * A later version of the queried artifact is available.
	     */
		YES("yes"),
        /**
         * No later version of the queried artifact is available.
         */		
		NO("no"),
        /**
         * For some reason the server was unable to figure out whether
         * a later version was available (maybe because the version number syntax
         * was not recognized and thus comparison failed).
         */ 		
		MAYBE("maybe"),
		/**
		 * The artifact was not found in any repository.
		 */
		NOT_FOUND("not_found");
		
		public final String text;

		private UpdateAvailable(String text) {
			this.text = text;
		}
		
		public static UpdateAvailable fromString(String s)
		{
			for ( UpdateAvailable v : values() ) {
				if ( v.text.equals(s) ) {
					return v;
				}
			}
			return UpdateAvailable.NOT_FOUND;
		}
	}

	public Artifact artifact;
	public Version currentVersion;
	public Version latestVersion;
	public UpdateAvailable updateAvailable;
	
	@Override
	public String toString() {
	    return "ArtifactResponse[ updateAvailable: "+updateAvailable+", current_version: "+currentVersion+" latest_version: "+latestVersion+", artifact: "+artifact+" ]";
	}
	
	public boolean hasCurrentVersion() {
	    return currentVersion != null;
	}
	
    public boolean hasLatestVersion() {
        return latestVersion != null;
    }	
}
