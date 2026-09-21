package com.axalotl.async.common.entity.query;

import com.axalotl.async.common.parallelised.fastutil.FastUtilHackUtil;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.AbstractObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Live lookup view with immutable, weakly consistent membership for each iterator. */
public final class EntityLookupView<T> extends AbstractObjectCollection<T> {
    private final Int2ObjectMap<T> entities;
    private final AtomicReference<Snapshot<T>> snapshot = new AtomicReference<>(new Snapshot<>(null));

    public EntityLookupView(Int2ObjectMap<T> entities) {
        this.entities = entities;
    }

    public boolean isFor(Int2ObjectMap<?> map) {
        return entities == map;
    }

    public void invalidate() {
        snapshot.set(new Snapshot<>(null));
    }

    @Override
    public ObjectIterator<T> iterator() {
        Snapshot<T> current = snapshot.get();
        if (current.entities() == null) {
            Snapshot<T> collected = new Snapshot<>(List.copyOf(entities.values()));
            // Invalidation replaces the token, so an older builder cannot retain removed entities.
            snapshot.compareAndSet(current, collected);
            current = collected;
        }
        return FastUtilHackUtil.itrWrap(current.entities());
    }

    @Override
    public int size() {
        return entities.size();
    }

    @Override
    public boolean contains(Object entity) {
        return entities.containsValue(entity);
    }

    private record Snapshot<T>(List<T> entities) {}
}
