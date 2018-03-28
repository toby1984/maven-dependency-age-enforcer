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

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class APIImplDestructionListener implements ServletContextListener
{
    private static final Logger LOG = LogManager.getLogger(APIImplDestructionListener.class);
    
    public APIImplDestructionListener()
    {
        LOG.info("APIImplDestructionListener(): Listener created");
    }
    
    @Override
    public void contextDestroyed(ServletContextEvent sce) 
    {
        LOG.info("contextDestroyed(): Servlet context is being destroyed");
        try 
        {
            APIImplHolder.getInstance().getImpl().close();
        } 
        catch (Exception e) 
        {
            LOG.error("contextDestroyed(): Caught ",e); 
        }
    }

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        // nop
    } 
}
