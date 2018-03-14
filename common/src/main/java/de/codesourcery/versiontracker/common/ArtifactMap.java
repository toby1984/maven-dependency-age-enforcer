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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.Validate;

/**
 * A container that stores values indexed by artifact group ID and artifact ID.
 *
 * @author tobias.gierke@code-sourcery.de
 * @param T type of values stored in this container
 */
public class ArtifactMap<T> 
{
    private Map<String,Map<String,T>> data = new HashMap<>();
    private int size;
    
    /**
     * Visits all values in this container.
     * 
     * @param visitor
     */
    public void visitValues(Consumer<T> visitor) 
    {
        Validate.notNull(visitor,"visitor must not be NULL");
        for ( Entry<String, Map<String, T>> mapEntries : data.entrySet() ) 
        {
            for ( Entry<String, T> entries2 : mapEntries.getValue().entrySet() ) 
            {
                visitor.accept( entries2.getValue() );
            }
        }
    }
    
    /**
     * Removes a values from this container.
     * 
     * @param groupId group ID, must not be <code>null</code> or blank
     * @param artifactId artifact ID, must not be <code>null</code> or blank
     * @return removed value or <code>null</code> if the value was not found in this container
     */
    public T remove(String groupId,String artifactId) 
    {
        Validate.notBlank(groupId,"groupId must not be NULL");
        Validate.notBlank(artifactId,"artifactId must not be NULL");           

    	T result = null;
        Map<String, T> map = data.get( groupId );
        if ( map != null ) {
            result = map.remove( artifactId );
            if ( result != null ) {
            	size--;
            	if ( size < 0 ) {
            		throw new IllegalStateException("Size should never become negative");
            	}
            }
        }
        return result ;
    }
    
    /**
     * Returns a {@link Stream} over this collection's values.
     * 
     * @return
     */
    public Stream<T> stream() 
    {
        final Spliterator<T> it = new Spliterator<T>() 
        {
            private Iterator<Entry<String, Map<String, T>>> it1 = data.entrySet().iterator();
            
            private Iterator<Entry<String, T>> it2;
            
            private T next() {
                if ( it2 != null && it2.hasNext() ) {
                    return it2.next().getValue();
                }
                if ( it1.hasNext() ) 
                {
                    it2 = it1.next().getValue().entrySet().iterator();
                    if ( it2.hasNext() ) {
                        return it2.next().getValue();
                    }
                }
                return null;
            }
            
            @Override
            public int characteristics() {
                return 0;
            }

            @Override
            public long estimateSize() {
                return Long.MAX_VALUE;
            }

            @Override
            public boolean tryAdvance(Consumer<? super T> consumer) {
                T value = next();
                if ( value != null ) {
                    consumer.accept( value );
                }
                return value != null;
            }

            @Override
            public Spliterator<T> trySplit() {
                return null;
            }};
        return StreamSupport.stream(it, true );
    }
    
    /**
     * Removes all values from this container.
     */
    public void clear() {
        this.data = new HashMap<>();
        size = 0;
    }
    
    /**
     * Returns the number of elements in this container.
     * 
     * @return
     */
    public int size() {
        return size;
    }
    
    /**
     * Returns whether this container holds no elements.
     * 
     * @return
     */
    public boolean isEmpty() {
        return data.isEmpty();
    }
    
    /**
     * Checks whether this container holds a value for a given group ID and artifact ID.
     * 
     * @param groupId group ID, must not be <code>null</code> or blank
     * @param artifactId artifact ID, must not be <code>null</code> or blank
     * @return
     */
    public boolean contains(String groupId,String artifactId) 
    {
        Validate.notBlank(groupId,"groupId must not be NULL");
        Validate.notBlank(artifactId,"artifactId must not be NULL");   

        final Map<String, T> map = data.get( groupId );
        if ( map != null ) {
            return map.containsKey( artifactId );
        }
        return false;
    }
    
    /**
     * Stores a value associated with a given group ID and artifact ID.
     * 
     * @param groupId group ID, must not be <code>null</code> or blank
     * @param artifactId artifact ID, must not be <code>null</code> or blank
     * @param value value to store, never <code>null</code>
     * @return old value that was already stored with this group ID and artifact ID or <code>null</code>
     */
    public T put(String groupId,String artifactId,T value) {
        Validate.notBlank(groupId,"groupId must not be NULL");
        Validate.notBlank(artifactId,"artifactId must not be NULL");        
        Validate.notNull(value,"value must not be NULL");
        
        Map<String, T> map = data.get( groupId );
        if ( map == null ) {
            map = new HashMap<>();
            data.put( groupId, map );
        }
        T existing = map.put( artifactId, value );
        if ( existing == null ) {
            size++;
        }
        return existing;
    }
    
    /**
     * Retrieves a value by group ID and artifact ID.
     * 
     * @param groupId group ID, must not be <code>null</code> or blank
     * @param artifactId artifact ID, must not be <code>null</code> or blank
     * 
     * @return value or <code>null</code>
     */
    public T get(String groupId,String artifactId) {
        Validate.notBlank(groupId,"groupId must not be NULL");
        Validate.notBlank(artifactId,"artifactId must not be NULL");           
        final Map<String, T> map = data.get( groupId );
        if ( map != null ) {
            return map.get( artifactId );
        }
        return null;
    }
}