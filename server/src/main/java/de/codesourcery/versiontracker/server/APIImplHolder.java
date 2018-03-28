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
package de.codesourcery.versiontracker.server;

import de.codesourcery.versiontracker.common.server.APIImpl;
import de.codesourcery.versiontracker.common.server.APIImpl.Mode;

public class APIImplHolder
{
    private static final APIImplHolder INSTANCE = new APIImplHolder();
    
    private final APIImpl apiImpl;
    
    private APIImplHolder() 
    {
        apiImpl = new APIImpl(Mode.SERVER);
        apiImpl.init(false,false);           
    }
    
    public static synchronized APIImplHolder getInstance() 
    {
        return INSTANCE;
    }
    
    public APIImpl getImpl() {
        return apiImpl;
    }
}