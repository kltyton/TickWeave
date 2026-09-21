package com.axalotl.async.common.entity.task;

import java.util.IdentityHashMap;
import java.util.concurrent.locks.LockSupport;

/** Loans execution resources across synchronous waits without allowing simultaneous writers. */
final class ExecutionResources {
    private static final Object LOCK = new Object();
    private static final Resource[] NONE = new Resource[0];
    private static final ThreadLocal<IdentityHashMap<Resource, Integer>> HELD = ThreadLocal.withInitial(() -> new IdentityHashMap<>(2));
    private static final IdentityHashMap<Thread, Boolean> WAITERS = new IdentityHashMap<>();

    private ExecutionResources() {}

    static boolean isEmpty() { return HELD.get().isEmpty(); }
    static boolean holds(Resource resource) { return HELD.get().containsKey(resource); }
    static void includeHeld(IdentityHashMap<Resource, Boolean> resources) {
        for (Resource resource : HELD.get().keySet()) resources.put(resource, Boolean.TRUE);
    }
    static void clearWaiter() { synchronized (LOCK) { WAITERS.remove(Thread.currentThread()); } }

    static Scope tryOpen(Resource[] resources) {
        synchronized (LOCK) {
            if (!available(resources)) {
                WAITERS.put(Thread.currentThread(), Boolean.TRUE);
                return null;
            }
            WAITERS.remove(Thread.currentThread());
            add(resources);
            return new Scope(resources);
        }
    }

    static Scope open(Resource[] resources, boolean mayAssist, Runnable pump) {
        Scope immediate = tryOpen(resources);
        if (immediate != null) return immediate;
        Suspension suspended = suspend();
        Throwable failure = suspended.restore(resources, mayAssist, pump);
        if (failure != null) {
            new Scope(resources).close();
            rethrow(failure);
        }
        return new Scope(resources);
    }

    static Suspension suspend() {
        IdentityHashMap<Resource, Integer> saved = HELD.get();
        synchronized (LOCK) {
            for (Resource resource : saved.keySet()) resource.runner = null;
            HELD.set(new IdentityHashMap<>(2));
            wake();
        }
        CooperativeTask.signalReady();
        return new Suspension(saved);
    }

    private static boolean available(Resource[] resources) {
        Thread thread = Thread.currentThread();
        for (Resource resource : resources) if (resource.runner != null && resource.runner != thread) return false;
        return true;
    }

    private static void add(Resource[] resources) {
        IdentityHashMap<Resource, Integer> held = HELD.get();
        Thread thread = Thread.currentThread();
        for (Resource resource : resources) {
            resource.runner = thread;
            held.merge(resource, 1, Integer::sum);
        }
    }

    private static void wake() { WAITERS.keySet().forEach(LockSupport::unpark); }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    static final class Resource { private Thread runner; }

    static final class Scope implements AutoCloseable {
        private final Resource[] resources;
        Scope(Resource[] resources) { this.resources = resources; }

        @Override public void close() {
            synchronized (LOCK) {
                IdentityHashMap<Resource, Integer> held = HELD.get();
                for (Resource resource : resources) {
                    int count = held.get(resource);
                    if (count == 1) {
                        held.remove(resource);
                        resource.runner = null;
                    } else held.put(resource, count - 1);
                }
                wake();
            }
            CooperativeTask.signalReady();
        }
    }

    static final class Suspension {
        private final IdentityHashMap<Resource, Integer> saved;
        Suspension(IdentityHashMap<Resource, Integer> saved) { this.saved = saved; }

        void resume(boolean mayAssist, Runnable pump) { rethrow(restore(NONE, mayAssist, pump)); }

        private Throwable restore(Resource[] additional, boolean mayAssist, Runnable pump) {
            Thread thread = Thread.currentThread();
            boolean interrupted = false;
            Throwable failure = null;
            try {
                while (true) {
                    synchronized (LOCK) {
                        boolean ready = available(additional);
                        if (ready) {
                            for (Resource resource : saved.keySet()) {
                                if (resource.runner != null && resource.runner != thread) { ready = false; break; }
                            }
                        }
                        if (ready) {
                            if (!HELD.get().isEmpty()) throw new IllegalStateException("Callback leaked execution resources");
                            HELD.set(saved);
                            for (Resource resource : saved.keySet()) resource.runner = thread;
                            add(additional);
                            return failure;
                        }
                        WAITERS.put(thread, Boolean.TRUE);
                    }
                    interrupted |= Thread.interrupted();
                    boolean progressed = false;
                    try {
                        progressed = mayAssist ? CooperativeTask.assist() : CooperativeTask.assistOwned();
                        pump.run();
                    } catch (RuntimeException | Error error) {
                        if (failure == null) failure = error;
                        else if (failure != error) failure.addSuppressed(error);
                    }
                    if (!progressed) LockSupport.parkNanos(50_000);
                }
            } finally {
                synchronized (LOCK) { WAITERS.remove(thread); }
                if (interrupted) thread.interrupt();
            }
        }
    }
}
