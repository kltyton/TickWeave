package com.axalotl.async.common.entity.task;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/** Serializes each owner's execution while permitting synchronous callbacks during a wait. */
public final class CooperativeTask {
    private static final Runnable NOTHING = () -> {};
    private static final ThreadLocal<ArrayList<CooperativeTask>> OWNED = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final ConcurrentLinkedQueue<CooperativeTask> READY = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean DISPATCHED = new AtomicBoolean();
    private static final AtomicLong SIGNALS = new AtomicLong();
    private static volatile Executor executor;
    private final AtomicReference<Thread> owner = new AtomicReference<>();
    private final ExecutionResources.Resource resource = new ExecutionResources.Resource();
    private final Context rootContext = new Context(new ExecutionResources.Resource[] {resource});
    private final ConcurrentLinkedQueue<Request<?>> requests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Thread> entrants = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean queued = new AtomicBoolean();

    public static void setExecutor(Executor value) { executor = value; }
    static Context currentContext() { return CURRENT.get(); }
    public boolean isHeld() { return ExecutionResources.holds(resource); }

    private static Context context(Context inherited) {
        ArrayList<CooperativeTask> owned = OWNED.get();
        if (inherited == null && owned.size() == 1 && ExecutionResources.isEmpty()) return owned.get(0).rootContext;
        IdentityHashMap<ExecutionResources.Resource, Boolean> resources = new IdentityHashMap<>(
                Math.max(2, owned.size() + (inherited == null ? 0 : inherited.resources.length)));
        if (inherited != null) for (var item : inherited.resources) resources.put(item, Boolean.TRUE);
        for (CooperativeTask task : owned) resources.put(task.resource, Boolean.TRUE);
        ExecutionResources.includeHeld(resources);
        return new Context(resources.keySet().toArray(ExecutionResources.Resource[]::new));
    }

    private static <T> T inContext(Context inherited, Supplier<T> action, boolean mayAssist, Runnable pump) {
        Context next = context(inherited);
        try (var ignored = ExecutionResources.open(next.resources, mayAssist, pump)) {
            return execute(next, action);
        }
    }

    private static <T> T execute(Context context, Supplier<T> action) {
        Context previous = CURRENT.get();
        CURRENT.set(context);
        try { return action.get(); }
        finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static Runnable contextual(Runnable action) {
        Context captured = CURRENT.get();
        return captured == null ? action : new Contextual(action, context(captured));
    }

    public static Runnable pollAllowed(ConcurrentLinkedQueue<Runnable> queue) { return queue.poll(); }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> action, Executor target) {
        CompletableFuture<T> result = new CompletableFuture<>();
        target.execute(contextual(() -> {
            try { result.complete(action.get()); }
            catch (RuntimeException | Error failure) { result.completeExceptionally(failure); }
        }));
        return result;
    }

    public static <T> CompletableFuture<T> composeAsync(Supplier<CompletableFuture<T>> action, Executor target) {
        CompletableFuture<T> result = new CompletableFuture<>();
        target.execute(contextual(() -> {
            try {
                action.get().whenComplete((value, failure) -> {
                    if (failure == null) result.complete(value);
                    else result.completeExceptionally(failure);
                });
            } catch (RuntimeException | Error failure) { result.completeExceptionally(failure); }
        }));
        return result;
    }

    public void runOwned(Runnable action) {
        if (owner.get() == Thread.currentThread()) {
            if (ExecutionResources.holds(resource)) action.run();
            else inContext(CURRENT.get(), () -> { action.run(); return null; }, true, NOTHING);
            return;
        }
        Thread thread = Thread.currentThread();
        boolean interrupted = false;
        boolean waiting = false;
        ExecutionResources.Suspension suspended = null;
        try {
            while (!enter()) {
                if (!waiting) {
                    entrants.add(thread);
                    waiting = true;
                    suspended = ExecutionResources.suspend();
                }
                interrupted |= Thread.interrupted();
                if (!assist()) LockSupport.parkNanos(50_000);
            }
            try {
                if (suspended != null) {
                    ExecutionResources.Suspension pending = suspended;
                    suspended = null;
                    pending.resume(true, NOTHING);
                }
                inContext(CURRENT.get(), () -> { action.run(); return null; }, true, NOTHING);
            } finally { leave(); }
        } finally {
            if (suspended != null) suspended.resume(true, NOTHING);
            if (waiting) entrants.remove(thread);
            if (interrupted) thread.interrupt();
        }
    }

    public <T> T call(Supplier<T> action, boolean mayAssist, Runnable pump) {
        return call(action, mayAssist, mayAssist, pump);
    }

    public <T> T call(Supplier<T> action, boolean mayEnter, boolean mayAssist, Runnable pump) {
        if (ExecutionResources.holds(resource)) return action.get();
        if (owner.get() == Thread.currentThread()) {
            return ExecutionResources.holds(resource) ? action.get() : inContext(CURRENT.get(), action, mayAssist, pump);
        }
        if (mayEnter && enter()) {
            try { return inContext(CURRENT.get(), action, mayAssist, pump); }
            finally { leave(); }
        }
        Request<T> request = new Request<>(action, CURRENT.get());
        requests.add(request);
        ready(true);
        return await(request.result, mayAssist, pump);
    }

    /** Acquires this resource without changing the caller's required execution thread. */
    public <T> T onThread(Supplier<T> action, boolean mayAssist, Runnable pump) {
        return ExecutionResources.holds(resource) ? action.get() : inContext(rootContext, action, mayAssist, pump);
    }

    static <T> T onThreads(Iterable<CooperativeTask> participants, Supplier<T> action, boolean mayAssist, Runnable pump) {
        IdentityHashMap<ExecutionResources.Resource, Boolean> resources = new IdentityHashMap<>();
        for (var resource : context(CURRENT.get()).resources) resources.put(resource, Boolean.TRUE);
        for (CooperativeTask participant : participants) resources.put(participant.resource, Boolean.TRUE);
        Context next = new Context(resources.keySet().toArray(ExecutionResources.Resource[]::new));
        try (var ignored = ExecutionResources.open(next.resources, mayAssist, pump)) {
            return execute(next, action);
        }
    }

    public static <T> T await(CompletableFuture<T> result, boolean mayAssist, Runnable pump) {
        boolean interrupted = false;
        if (!result.isDone()) {
            Thread waiter = Thread.currentThread();
            result.whenComplete((value, failure) -> LockSupport.unpark(waiter));
            ExecutionResources.Suspension suspended = ExecutionResources.suspend();
            try {
                while (!result.isDone()) {
                    interrupted |= Thread.interrupted();
                    boolean progressed = mayAssist ? assist() : assistOwned();
                    pump.run();
                    if (!progressed && !result.isDone()) LockSupport.parkNanos(50_000);
                }
            } finally {
                try { suspended.resume(mayAssist, pump); }
                finally {
                    ExecutionResources.clearWaiter();
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
        try { return result.join(); }
        catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }

    public static boolean assist() {
        boolean progressed = assistOwned();
        for (int i = 0; i < 16; i++) {
            CooperativeTask next = READY.poll();
            if (next == null) break;
            next.queued.set(false);
            if (next.requests.isEmpty()) continue;
            if (next.enter()) {
                try { progressed |= next.drain(); }
                finally { next.leave(false); }
            } else {
                Thread runner = next.owner.get();
                if (runner != null) LockSupport.unpark(runner);
            }
        }
        return progressed;
    }

    static boolean assistOwned() {
        boolean progressed = false;
        ArrayList<CooperativeTask> held = OWNED.get();
        for (int i = held.size() - 1; i >= 0; i--) progressed |= held.get(i).drain();
        return progressed;
    }

    private boolean enter() {
        if (!owner.compareAndSet(null, Thread.currentThread())) return false;
        OWNED.get().add(this);
        return true;
    }

    private void leave() { leave(true); }

    private void leave(boolean schedule) {
        ArrayList<CooperativeTask> held = OWNED.get();
        if (held.remove(held.size() - 1) != this) throw new IllegalStateException("Entity owner stack mismatch");
        owner.set(null);
        entrants.forEach(LockSupport::unpark);
        if (!requests.isEmpty()) ready(schedule);
    }

    private boolean drain() {
        boolean progressed = false;
        for (int i = 0; i < 32; i++) {
            Request<?> request = requests.peek();
            if (request == null) break;
            Context next = context(request.context);
            ExecutionResources.Scope scope = ExecutionResources.tryOpen(next.resources);
            if (scope == null) break;
            try (scope) {
                if (requests.poll() != request) throw new IllegalStateException("Multiple entity mailbox consumers");
                execute(next, () -> { request.execute(); return null; });
                progressed = true;
            }
        }
        return progressed;
    }

    private void ready(boolean schedule) {
        Thread runner = owner.get();
        if (runner != null) LockSupport.unpark(runner);
        if (!queued.compareAndSet(false, true)) return;
        READY.add(this);
        if (schedule) signalReady();
    }

    static void signalReady() {
        if (READY.isEmpty()) return;
        SIGNALS.incrementAndGet();
        Executor pool = executor;
        if (pool != null && !READY.isEmpty() && DISPATCHED.compareAndSet(false, true)) {
            try {
                pool.execute(() -> {
                    long observed = SIGNALS.get();
                    boolean progressed = false;
                    try { progressed = assist(); }
                    finally {
                        ExecutionResources.clearWaiter();
                        DISPATCHED.set(false);
                        if (progressed || observed != SIGNALS.get()) signalReady();
                    }
                });
            }
            catch (RejectedExecutionException saturated) {
                DISPATCHED.set(false);
                // Waiting owners and resource releases revisit requests when the executor is saturated.
            }
        }
    }

    static final class Context {
        final ExecutionResources.Resource[] resources;
        Context(ExecutionResources.Resource[] resources) { this.resources = resources; }
    }

    private record Contextual(Runnable action, Context context) implements Runnable {
        @Override public void run() {
            inContext(context, () -> { action.run(); return null; }, true, NOTHING);
        }
    }

    private static final class Request<T> {
        final Supplier<T> action;
        final Context context;
        final CompletableFuture<T> result = new CompletableFuture<>();
        Request(Supplier<T> action, Context context) { this.action = action; this.context = context; }
        void execute() {
            try { result.complete(action.get()); }
            catch (RuntimeException | Error failure) { result.completeExceptionally(failure); }
        }
    }
}
