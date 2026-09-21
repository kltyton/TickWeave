package com.axalotl.async.common.parallelised;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.concurrent.CopyOnWriteArrayList;

/** Registration-ordered snapshots for small sets that are traversed far more often than changed. */
public final class ConcurrentOrderedSet<E> extends AbstractSet<E> {
    private final CopyOnWriteArrayList<E> elements = new CopyOnWriteArrayList<>();

    @Override
    public int size() { return elements.size(); }

    @Override
    public boolean contains(Object value) { return elements.contains(Objects.requireNonNull(value)); }

    @Override
    public boolean add(E value) { return elements.addIfAbsent(Objects.requireNonNull(value)); }

    @Override
    public boolean remove(Object value) { return elements.remove(Objects.requireNonNull(value)); }

    @Override
    public void clear() { elements.clear(); }

    @Override
    public Iterator<E> iterator() {
        Iterator<E> snapshot = elements.iterator();
        return new Iterator<>() {
            private E current;
            private boolean canRemove;

            @Override
            public boolean hasNext() { return snapshot.hasNext(); }

            @Override
            public E next() {
                current = snapshot.next();
                canRemove = true;
                return current;
            }

            @Override
            public void remove() {
                if (!canRemove) throw new IllegalStateException();
                elements.remove(current);
                canRemove = false;
            }
        };
    }

    @Override
    public Spliterator<E> spliterator() { return elements.spliterator(); }
}
