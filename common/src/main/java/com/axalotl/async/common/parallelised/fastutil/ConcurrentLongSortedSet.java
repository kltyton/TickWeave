package com.axalotl.async.common.parallelised.fastutil;

import it.unimi.dsi.fastutil.longs.*;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * A thread-safe implementation of LongSortedSet backed by ConcurrentSkipListSet.
 * Provides concurrent access and maintains elements in sorted order.
 */
public final class ConcurrentLongSortedSet implements LongSortedSet {

    private final NavigableSet<Long> backing;

    /**
     * Creates a new empty concurrent sorted set
     */
    public ConcurrentLongSortedSet() {
        backing = new ConcurrentSkipListSet<>();
    }

    private ConcurrentLongSortedSet(NavigableSet<Long> backing) {
        this.backing = backing;
    }

    /**
     * Creates a new concurrent sorted set containing elements from the given collection
     *
     * @param collection initial elements
     * @throws NullPointerException if collection is null
     */
    public ConcurrentLongSortedSet(Collection<Long> collection) {
        this();
        addAll(Objects.requireNonNull(collection, "Initial collection cannot be null"));
    }

    @Override
    public LongBidirectionalIterator iterator(long fromElement) {
        Long previous = backing.floor(fromElement);
        Iterator<Long> forward = previous == null ? backing.iterator()
                : backing.higher(fromElement) == null ? Collections.emptyIterator()
                : backing.tailSet(fromElement, false).iterator();
        return new SetIterator(forward, previous);
    }

    @Override
    public @NotNull LongBidirectionalIterator iterator() {
        return new SetIterator(backing.iterator(), null);
    }

    public LongIterator iterator(long fromInclusive, long toExclusive) {
        return LongIterators.asLongIterator(backing.subSet(fromInclusive, true, toExclusive, false).iterator());
    }

    private final class SetIterator implements LongBidirectionalIterator {
        private Iterator<Long> forward;
        private Long previous;
        private Long current;
        private boolean movedForward;

        private SetIterator(Iterator<Long> forward, Long previous) {
            this.forward = forward;
            this.previous = previous;
        }

        @Override
        public boolean hasNext() { return forward.hasNext(); }

        @Override
        public long nextLong() {
            current = forward.next();
            previous = current;
            movedForward = true;
            return current;
        }

        @Override
        public boolean hasPrevious() { return previous != null; }

        @Override
        public long previousLong() {
            if (previous == null) throw new NoSuchElementException();
            current = previous;
            previous = backing.lower(current);
            forward = backing.tailSet(current, true).iterator();
            movedForward = false;
            return current;
        }

        @Override
        public void remove() {
            if (current == null) throw new IllegalStateException();
            if (movedForward) {
                forward.remove();
            } else {
                backing.remove(current);
                forward = backing.tailSet(current, false).iterator();
            }
            if (Objects.equals(previous, current)) previous = backing.lower(current);
            current = null;
        }
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public boolean isEmpty() {
        return backing.isEmpty();
    }

    @NotNull
    @Override
    public Object @NotNull [] toArray() {
        return backing.toArray();
    }

    @NotNull
    @Override
    public <T> T @NotNull [] toArray(@NotNull T @NotNull [] array) {
        return backing.toArray(array);
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> collection) {
        return backing.containsAll(collection);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends Long> collection) {
        return backing.addAll(collection);
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> collection) {
        return backing.removeAll(collection);
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> collection) {
        return backing.retainAll(collection);
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public boolean add(long key) {
        return backing.add(key);
    }

    @Override
    public boolean contains(long key) {
        return backing.contains(key);
    }

    @Override
    public long[] toLongArray() {
        return longStream().toArray();
    }

    @Override
    public long[] toArray(long[] array) {
        long[] result = toLongArray();
        if (array.length < result.length) {
            return result;
        }
        System.arraycopy(result, 0, array, 0, result.length);
        if (array.length > result.length) {
            array[result.length] = 0L;
        }
        return array;
    }

    @Override
    public boolean addAll(LongCollection c) {
        boolean modified = false;
        for (LongIterator it = c.iterator(); it.hasNext(); ) {
            if (backing.add(it.nextLong())) {
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public boolean containsAll(LongCollection c) {
        for (LongIterator it = c.iterator(); it.hasNext(); ) {
            if (!backing.contains(it.nextLong())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean removeAll(LongCollection c) {
        boolean modified = false;
        for (LongIterator it = c.iterator(); it.hasNext(); ) {
            if (backing.remove(it.nextLong())) {
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public boolean retainAll(LongCollection c) {
        return backing.retainAll(c);
    }

    @Override
    public boolean remove(long k) {
        return backing.remove(k);
    }

    @Override
    public LongSortedSet subSet(long fromElement, long toElement) {
        return new ConcurrentLongSortedSet(backing.subSet(fromElement, true, toElement, false));
    }

    @Override
    public LongSortedSet headSet(long toElement) {
        return new ConcurrentLongSortedSet(backing.headSet(toElement, false));
    }

    @Override
    public LongSortedSet tailSet(long fromElement) {
        return new ConcurrentLongSortedSet(backing.tailSet(fromElement, true));
    }

    @Override
    public LongComparator comparator() {
        return null;
    }

    @Override
    public long firstLong() {
        return backing.first();
    }

    @Override
    public long lastLong() {
        return backing.last();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof LongSortedSet that && backing.equals(that));
    }

    @Override
    public int hashCode() {
        return backing.hashCode();
    }

    @Override
    public String toString() {
        return backing.toString();
    }
}
