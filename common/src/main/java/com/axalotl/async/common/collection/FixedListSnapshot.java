package com.axalotl.async.common.collection;

import java.io.Serializable;
import java.util.AbstractList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/** Keeps configuration-list membership fixed without copying the mutable elements themselves. */
public final class FixedListSnapshot<E> extends AbstractList<E> implements RandomAccess, Serializable {
    private static final long serialVersionUID = 1L;
    private final Object[] elements;

    private FixedListSnapshot(Object[] elements) {
        this.elements = elements;
    }

    public static <E> List<E> copyOf(Collection<? extends E> values) {
        return new FixedListSnapshot<>(values.toArray());
    }

    @Override
    @SuppressWarnings("unchecked")
    public E get(int index) {
        return (E) elements[Objects.checkIndex(index, elements.length)];
    }

    @Override
    public int size() {
        return elements.length;
    }
}
