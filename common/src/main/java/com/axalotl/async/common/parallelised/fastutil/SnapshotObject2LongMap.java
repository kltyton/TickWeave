package com.axalotl.async.common.parallelised.fastutil;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;

/** Native scalar operations for analyzed private tables with read-only key traversal. */
public final class SnapshotObject2LongMap<K> extends Object2LongOpenHashMap<K> {
    @Override
    public synchronized boolean isEmpty() { return super.isEmpty(); }

    @Override
    public synchronized long getLong(Object key) { return super.getLong(key); }

    @Override
    public synchronized long putIfAbsent(K key, long value) { return super.putIfAbsent(key, value); }

    @Override
    public synchronized long removeLong(Object key) { return super.removeLong(key); }

    @Override
    public synchronized ObjectSet<K> keySet() {
        // No quest/entity callback runs while this monitor is held.
        return super.isEmpty() ? ObjectSets.emptySet() : new ObjectArraySet<>(super.keySet());
    }
}
