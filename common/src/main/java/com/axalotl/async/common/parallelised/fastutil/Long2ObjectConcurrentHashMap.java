package com.axalotl.async.common.parallelised.fastutil;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.AbstractLong2ObjectMap.BasicEntry;
import it.unimi.dsi.fastutil.longs.AbstractLongSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.LongFunction;

/**
 * A thread-safe implementation of Long2ObjectMap using ConcurrentHashMap as backing storage.
 * Provides concurrent access and high performance for long-to-object mappings.
 *
 * @param <V> the type of values maintained by this map
 */
public final class Long2ObjectConcurrentHashMap<V> implements Long2ObjectMap<V> {

    // Packed coordinates collide under Long.hashCode; the reversible mix keeps every original key distinct.
    private final ConcurrentHashMap<Long, V> backing;
    private V defaultReturnValue;

    /**
     * Creates a new empty concurrent map with default initial capacity
     */
    public Long2ObjectConcurrentHashMap() {
        this.backing = new ConcurrentHashMap<>();
    }

    @Override
    public V get(long key) {
        V value = backing.get(HashCommon.mix(key));
        return value == null ? defaultReturnValue : value;
    }

    @Override
    public boolean isEmpty() {
        return backing.isEmpty();
    }

    @Override
    public boolean containsValue(Object value) {
        return backing.containsValue(value);
    }

    @Override
    public void putAll(@NotNull Map<? extends Long, ? extends V> m) {
        Objects.requireNonNull(m, "Source map cannot be null");
        m.forEach((key, value) -> backing.put(HashCommon.mix(key.longValue()), value));
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public void defaultReturnValue(V rv) {
        this.defaultReturnValue = rv;
    }

    @Override
    public V defaultReturnValue() {
        return defaultReturnValue;
    }

    @Override
    public ObjectSet<Entry<V>> long2ObjectEntrySet() {
        return new AbstractObjectSet<>() {
            @Override
            public int size() {
                return backing.size();
            }

            @Override
            public void clear() {
                backing.clear();
            }

            @Override
            public boolean contains(Object object) {
                return object instanceof Map.Entry<?, ?> entry && entry.getKey() instanceof Long key
                        && entry.getValue() != null && entry.getValue().equals(backing.get(HashCommon.mix(key.longValue())));
            }

            @Override
            public boolean remove(Object object) {
                return object instanceof Map.Entry<?, ?> entry && entry.getKey() instanceof Long key
                        && entry.getValue() != null && backing.remove(HashCommon.mix(key.longValue()), entry.getValue());
            }

            @Override
            public ObjectIterator<Entry<V>> iterator() {
                var iterator = backing.entrySet().iterator();
                return new ObjectIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public Entry<V> next() {
                        var entry = iterator.next();
                        return new BasicEntry<>(HashCommon.invMix(entry.getKey().longValue()), entry.getValue()) {
                            @Override
                            public V setValue(V value) {
                                V previous = entry.setValue(value);
                                this.value = value;
                                return previous;
                            }
                        };
                    }

                    @Override
                    public void remove() {
                        iterator.remove();
                    }
                };
            }
        };
    }

    @Override
    public @NotNull LongSet keySet() {
        return new AbstractLongSet() {
            @Override
            public int size() {
                return backing.size();
            }

            @Override
            public boolean contains(long key) {
                return backing.containsKey(HashCommon.mix(key));
            }

            @Override
            public boolean remove(long key) {
                return backing.remove(HashCommon.mix(key)) != null;
            }

            @Override
            public void clear() {
                backing.clear();
            }

            @Override
            public LongIterator iterator() {
                var iterator = backing.keySet().iterator();
                return new LongIterator() {
                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public long nextLong() {
                        return HashCommon.invMix(iterator.next().longValue());
                    }

                    @Override
                    public void remove() {
                        iterator.remove();
                    }
                };
            }
        };
    }

    @Override
    public @NotNull ObjectCollection<V> values() {
        return FastUtilHackUtil.wrap(backing.values());
    }

    @Override
    public boolean containsKey(long key) {
        return backing.containsKey(HashCommon.mix(key));
    }

    @Override
    public V put(long key, V value) {
        V previous = backing.put(HashCommon.mix(key), value);
        return previous == null ? defaultReturnValue : previous;
    }

    @Override
    public V remove(long key) {
        V previous = backing.remove(HashCommon.mix(key));
        return (previous == null) ? defaultReturnValue : previous;
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public V computeIfAbsent(long key, @NotNull LongFunction<? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        V value = backing.computeIfAbsent(HashCommon.mix(key), ignored -> mappingFunction.apply(key));
        return value == null ? defaultReturnValue : value;
    }

    @Override
    public V compute(long key, @NotNull BiFunction<? super Long, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction, "Remapping function cannot be null");
        V value = backing.compute(HashCommon.mix(key), (ignored, previous) -> remappingFunction.apply(key, previous));
        return value == null ? defaultReturnValue : value;
    }

    public V getOrDefault(long key, V defaultValue) {
        V value = backing.get(HashCommon.mix(key));
        return value == null ? defaultValue : value;
    }

    /**
     * Associates the specified value with the specified key if no value is present
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value or defaultReturnValue if none
     */
    public V putIfAbsent(long key, V value) {
        V previous = backing.putIfAbsent(HashCommon.mix(key), value);
        return (previous == null) ? defaultReturnValue : previous;
    }

    /**
     * Removes the entry for the specified key only if it is currently mapped to the specified value
     *
     * @param key   key with which the specified value is associated
     * @param value value expected to be associated with the specified key
     * @return true if the value was removed
     */
    public boolean remove(long key, Object value) {
        return backing.remove(HashCommon.mix(key), value);
    }

    /**
     * Replaces the entry for the specified key only if it is currently mapped to the specified value
     *
     * @param key      key with which the specified value is associated
     * @param oldValue value expected to be associated with the specified key
     * @param newValue value to be associated with the specified key
     * @return true if the value was replaced
     */
    public boolean replace(long key, V oldValue, V newValue) {
        return backing.replace(HashCommon.mix(key), oldValue, newValue);
    }

    /**
     * Replaces the entry for the specified key only if it is currently mapped to some value
     *
     * @param key   key with which the specified value is associated
     * @param value value to be associated with the specified key
     * @return the previous value or defaultReturnValue if none
     */
    public V replace(long key, V value) {
        V previous = backing.replace(HashCommon.mix(key), value);
        return (previous == null) ? defaultReturnValue : previous;
    }

    @Override
    public boolean equals(Object object) {
        return object == this || object instanceof Map<?, ?> other && size() == other.size()
                && long2ObjectEntrySet().containsAll(other.entrySet());
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (var entry : backing.entrySet()) {
            hash += Long.hashCode(HashCommon.invMix(entry.getKey().longValue())) ^ entry.getValue().hashCode();
        }
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder("{");
        for (var entry : backing.entrySet()) {
            if (result.length() > 1) result.append(", ");
            result.append(HashCommon.invMix(entry.getKey().longValue())).append('=')
                    .append(entry.getValue() == this ? "(this Map)" : entry.getValue());
        }
        return result.append('}').toString();
    }

}
