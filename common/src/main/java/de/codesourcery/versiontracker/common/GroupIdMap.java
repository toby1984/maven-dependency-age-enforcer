package de.codesourcery.versiontracker.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.commons.lang3.Validate;

/**
 * A special map that supports wild-card lookup of items
 * by group ID.
 * @param <T>
 * @author tobias.gierke@code-sourcery.de
 */
public class GroupIdMap<T,X>
{
    private final Map<String, T> exactMatches = new HashMap<>();
    private final Map<String, T> wildcardMatches = new HashMap<>();

    public interface IAccumulator<IN,OUT> {
        void accumulate(IN in);
        OUT finish();
        OUT merge(OUT a, OUT b);
    }

    private final Supplier<IAccumulator<T,X>> accumulatorSupplier;

    public GroupIdMap(Supplier<IAccumulator<T,X>> accumulatorSupplier)
    {
        Validate.notNull( accumulatorSupplier, "accumulatorSupplier must not be null" );
        this.accumulatorSupplier = accumulatorSupplier;
    }

    @Override
    public boolean equals(Object o)
    {
        if ( this == o )
        {
            return true;
        }
        if ( !(o instanceof final GroupIdMap<?,?> that) )
        {
            return false;
        }
        final Map<String, T> exact = doCast( that.exactMatches );
        final Map<String, T> wildcard = doCast( that.wildcardMatches );
        return equals( exactMatches, exact ) && equals( wildcardMatches, wildcard );
    }

    private Map<String, T> doCast(Object exactMatches)
    {
        return (Map<String, T>) exactMatches;
    }

    private boolean equals(Map<String, T> m1, Map<String, T> m2) {

        if ( m1 == null || m2 == null ) {
            return m1 == m2;
        }
        if ( m1.size() != m2.size() ) {
            return false;
        }
        for ( final String k1 : m1.keySet() )
        {
            final T v1 = m1.get( k1 );
            final T v2 = m2.get( k1 );
            if ( ! equals( v1, v2 ) ) {
                return false;
            }
        }
        return true;
    }

    protected boolean equals(T a, T b) {
        return Objects.equals( a, b );
    }

    @Override
    public int hashCode()
    {
        return Objects.hash( exactMatches, wildcardMatches );
    }

    public X get(String groupId) {
        Validate.notBlank( groupId, "groupId must not be null or blank");

        final IAccumulator<T, X> acc = accumulatorSupplier.get();
        final Consumer<T> append = s1 -> {
            if ( s1 != null ) {
                acc.accumulate( s1 );
            }
        };
        append.accept( exactMatches.get( groupId ) );

        final String[] parts = groupId.split( "\\." );
        StringBuilder s = new StringBuilder();
        int i = 0;
        do
        {
            s.append( parts[i++] );
            append.accept( wildcardMatches.get( s.toString() ) );
            s.append( "." );
        } while (i < parts.length);

        return acc.finish();
    }

    public boolean isEmpty() {
        return exactMatches.isEmpty() && wildcardMatches.isEmpty();
    }

    /**
     *
     * @param groupIdPattern
     * @param value
     * @return <code>true</code> if this map did not already contain a value for this group ID
     */
    public boolean put(String groupIdPattern, T value ) {
        Validate.notBlank( groupIdPattern, "groupId must not be null or blank");
        Validate.notNull( value, "value must not be null" );

        final int idx = groupIdPattern.indexOf( "*" );
        if ( idx != -1 ) {
            // we only support wildcards at the end
            if ( groupIdPattern.length() < 3 || !groupIdPattern.endsWith( ".*" ) )
            {
                throw new IllegalArgumentException( "Unsupported pattern in group ID '" + groupIdPattern + "' - asterisk may only appear at the very end, preceded by a dot '.'" );
            }
            final String sub = groupIdPattern.substring( 0, groupIdPattern.length() - ".*".length() );
            if ( sub.endsWith("." ) ) {
                throw new IllegalArgumentException( "Invalid pattern in group ID '" + groupIdPattern + "' - sequence of multiple dots?" );
            }
            return addToMap( wildcardMatches, sub, value );
        }
        return addToMap( exactMatches, groupIdPattern, value );
    }

    private boolean addToMap( Map<String,T> map, String key, T value) {
        final T existing = map.get( key );
        if ( existing != null ) {
            if ( equals( existing, value ) ) {
                return false;
            }
            throw new IllegalStateException( "Trying to register different values for key '" + key + "' : "+existing+" <-> "+value );
        }
        map.put( key, value );
        return true;
    }

    private boolean setAdd(Set<T> set, T toAdd) {
        Validate.notNull( toAdd, "toAdd must not be null" );
        for ( final T existing : set )
        {
            if ( equals(existing, toAdd ) ) {
                return false;
            }
        }
        set.add( toAdd );
        return true;
    }

    public int size() {
        return exactMatches.size() + wildcardMatches.size();
    }

    public Map<String,X> getAsMap() {

        final Map<String, X> result = new HashMap<>();

        this.exactMatches.forEach( (key,value) -> {
            final IAccumulator<T, X> acc = accumulatorSupplier.get();
            acc.accumulate( value );
            final X existing = result.get( key );
            if ( existing != null ) {
                result.put( key, acc.merge( existing, acc.finish() ) );
            } else {
                result.put( key, acc.finish() );
            }
        });

        this.wildcardMatches.forEach( (key,value) -> {
            final IAccumulator<T, X> acc = accumulatorSupplier.get();
            acc.accumulate( value );
            final X existing = result.get( key );
            if ( existing != null ) {
                result.put( key, acc.merge( existing, acc.finish() ) );
            } else {
                result.put( key, acc.finish() );
            }
        });
        return result;
    }
}