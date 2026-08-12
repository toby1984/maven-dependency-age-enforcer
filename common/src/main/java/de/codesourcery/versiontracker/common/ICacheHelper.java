package de.codesourcery.versiontracker.common;

import java.util.function.Function;

/**
 * An item that may be stored in an {@link ArtifactMap}.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public interface ICacheHelper<T>
{
    static ICacheHelper<?> IMMUTABLE = new ICacheHelper<>()
    {
        @Override
        public Class<Object> typeWitness()
        {
            return Object.class; // dummy, actual value does not matter as long it's not NULL
        }

        @Override
        public Object createCopy(Object toCopy)
        {
            return toCopy;
        }

        @Override
        public boolean isMutable()
        {
            return false;
        }
    };

    /**
     * Helper method because Java uses type erasure so we cannot
     * implement an <code>equals()</code> method
     * based on the type parameter 'T' this interface accepts...
     * so {@link #matches(ICacheHelper)} it is and I'm using
     * this "type witness" to do the actual comparison.
     * @return
     */
    Class<T> typeWitness();

    default boolean matches(ICacheHelper<?> obj) {
        return obj instanceof ICacheHelper<?> helper && helper.typeWitness().equals(typeWitness());
    }

    /**
     * Returns a cache helper implementation for immutable instances.
     *
     * @return
     * @param <X>
     */
    static <X> ICacheHelper<X> immutable() {
        return (ICacheHelper<X>) IMMUTABLE;
    }

    static <X> ICacheHelper<X> mutable(Class<X> witness, Function<X,X> copyFunction) {
        return new ICacheHelper<X>()
        {
            @Override
            public Class<X> typeWitness()
            {
                return witness;
            }

            @Override
            public X createCopy(X toCopy)
            {
                return toCopy == null ? null : copyFunction.apply(toCopy);
            }
        };
    }

    /**
     * Returns whether this instance is mutable.
     * @return
     */
    default boolean isMutable() {
        return true;
    }

    /**
     * Create a copy of an instance.
     *
     * @return a copy of this instance.
     * @see #isMutable()
     */
    T createCopy(T toCopy);
}
