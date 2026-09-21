package com.axalotl.async.common.parallelised.utils;

import java.lang.ref.WeakReference;
import java.util.Objects;

/** Reuses the last value of a standard ThreadLocal accessed exclusively through this wrapper. */
public final class LastThreadLocal<T> extends ThreadLocal<T> {
    private final ThreadLocal<T> delegate;
    private volatile Entry<T> last;

    public LastThreadLocal(ThreadLocal<T> delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public T get() {
        Thread thread = Thread.currentThread();
        Entry<T> entry = last;
        if (entry != null && entry.thread().get() == thread) return entry.value();
        T value = delegate.get();
        last = new Entry<>(new WeakReference<>(thread), value);
        return value;
    }

    @Override
    public void set(T value) {
        delegate.set(value);
        last = new Entry<>(new WeakReference<>(Thread.currentThread()), value);
    }

    @Override
    public void remove() {
        delegate.remove();
        Entry<T> entry = last;
        if (entry != null && entry.thread().get() == Thread.currentThread()) last = null;
    }

    private record Entry<T>(WeakReference<Thread> thread, T value) {}
}
