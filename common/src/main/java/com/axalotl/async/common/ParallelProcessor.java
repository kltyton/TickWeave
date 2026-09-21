package com.axalotl.async.common;

import com.axalotl.async.common.config.AsyncConfig;
import com.axalotl.async.common.entity.task.CooperativeTask;
import com.axalotl.async.common.entity.task.EntityTasks;
import com.axalotl.async.common.utils.EntityTickCircuitBreaker;
import com.axalotl.async.common.utils.TickStats;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ParallelProcessor {
    public static final Logger LOGGER = LogManager.getLogger(ParallelProcessor.class);
    private static volatile MinecraftServer server;
    private static volatile Thread serverThread;
    public static final AtomicInteger currentEntities = new AtomicInteger();
    private static final AtomicInteger threadPoolID = new AtomicInteger();
    public static ExecutorService tickPool;
    private static volatile List<WeakReference<Thread>> tickThreads = List.of();
    private static volatile boolean isShuttingDown;
    private static final Object ENTITY_ADD_LOCK = new Object();
    private static final ConcurrentLinkedQueue<Runnable> entityCallbacks = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();
    private static final EntityTickCircuitBreaker circuitBreaker = new EntityTickCircuitBreaker();
    private static final LongAdder totalAsyncTicks = new LongAdder();
    private static final LongAdder totalAsyncFailures = new LongAdder();
    private static final LongAdder totalTimeoutWarnings = new LongAdder();
    private static final AtomicInteger lastWorkerCount = new AtomicInteger();
    public static final CostModel ENTITY_TICK_COST = new CostModel(25_000);
    public static final CostModel DESPAWN_COST = new CostModel(2_000);
    public static final CostModel GENERIC_COST = new CostModel(100_000);
    public static final CostModel SPAWN_COST = new CostModel(100_000);
    public static EntityTickCircuitBreaker getCircuitBreaker() { return circuitBreaker; }
    public static int getTotalAsyncTicks() { return totalAsyncTicks.intValue(); }
    public static int getTotalAsyncFailures() { return totalAsyncFailures.intValue(); }
    public static int getTotalTimeoutWarnings() { return totalTimeoutWarnings.intValue(); }
    public static int getLastWorkerCount() { return lastWorkerCount.get(); }

    public static void setupThreadPool(int parallelism, Class<?> asyncClass) {
        isShuttingDown = false;
        int workers = Math.max(1, parallelism);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(16, workers * 4)), runnable -> {
                    Thread thread = new Thread(runnable, "Async-Tick-Pool-Thread-" + threadPoolID.getAndIncrement());
                    registerThread("Async-Tick", thread);
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    thread.setContextClassLoader(asyncClass.getClassLoader());
                    return thread;
                });
        pool.prestartAllCoreThreads();
        tickPool = pool;
        CooperativeTask.setExecutor(pool);
        LOGGER.info("Initialized Pool with {} threads; adaptive batches and main-thread task pumping", workers);
    }

    public static synchronized void registerThread(String poolName, Thread thread) {
        if (!"Async-Tick".equals(poolName)) return;
        List<WeakReference<Thread>> registered = new ArrayList<>(tickThreads.size() + 1);
        for (WeakReference<Thread> reference : tickThreads) {
            Thread existing = reference.get();
            if (existing == thread) return;
            if (existing != null) registered.add(reference);
        }
        registered.add(new WeakReference<>(thread));
        tickThreads = List.copyOf(registered);
    }

    public static boolean isServerExecutionThread() {
        Thread current = Thread.currentThread();
        List<WeakReference<Thread>> registered = tickThreads;
        for (int i = 0; i < registered.size(); i++) {
            if (registered.get(i).get() == current) return true;
        }
        return false;
    }

    public static int getPoolSize() {
        return tickPool instanceof ThreadPoolExecutor pool ? pool.getCorePoolSize() : 0;
    }

    public static void callEntityTickBatch(ServerLevel world, List<Entity> entities) {
        callEntityTickBatch(world, entities, null);
    }

    public static void callEntityTickBatch(ServerLevel world, List<Entity> entities, List<Entity> despawnOnly) {
        lastWorkerCount.set(0);
        if (entities.isEmpty() && (despawnOnly == null || despawnOnly.isEmpty())) return;
        if (!canParallelize()) {
            if (despawnOnly != null) despawnOnly.forEach(ParallelProcessor::checkDespawn);
            for (Entity entity : entities) {
                if (despawnOnly != null) checkDespawn(entity);
                if (!entity.isRemoved() && entity.level() == world && !entity.isPassenger()) tickEntity(world, entity);
            }
            return;
        }
        EntityTasks.begin(world, entities, despawnOnly);
        try {
            List<Entity> all = new ArrayList<>();
            if (despawnOnly != null) all.addAll(despawnOnly);
            all.addAll(entities);
            Set<Entity> ticking = Collections.newSetFromMap(new IdentityHashMap<>());
            ticking.addAll(entities);
            List<Runnable> work = new ArrayList<>();
            for (EntityTasks.Group group : EntityTasks.groups(all)) {
                work.add(() ->
                        ENTITY_TICK_COST.measure(group.roots().size(), () ->
                                EntityTasks.run(group.roots().get(0), () -> {
                                    for (Entity entity : group.roots()) {
                                        if (despawnOnly != null) checkDespawn(entity);
                                        if (ticking.contains(entity) && !entity.isRemoved()
                                                && entity.level() == world && !entity.isPassenger()) tickEntity(world, entity);
                                    }
                                })));
            }
            runEntitySchedule(work);
        } finally {
            EntityTasks.end();
        }
    }

    private static void runEntitySchedule(List<Runnable> work) { finishParallel(submitParallel(work)); }

    /** Completes pre-batch ownership scans without pumping game callbacks through the barrier. */
    public static void prepareOwnership(List<Runnable> tasks) {
        if (tasks.isEmpty()) return;
        ExecutorService executor = tickPool;
        if (executor == null || getPoolSize() < 2 || tasks.size() == 1) {
            tasks.forEach(Runnable::run);
            return;
        }
        ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>(tasks);
        CountDownLatch done = new CountDownLatch(tasks.size());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable drain = () -> {
            Runnable task;
            while ((task = pending.poll()) != null) {
                try {
                    if (failure.get() == null) task.run();
                } catch (RuntimeException | Error problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    done.countDown();
                }
            }
        };
        for (int i = 0; i < Math.min(getPoolSize(), tasks.size() - 1); i++) {
            try { executor.execute(drain); }
            catch (RejectedExecutionException unavailable) { break; }
        }
        // The caller participates, so a full/shutting-down pool cannot strand this pure-data stage.
        drain.run();
        boolean interrupted = false;
        while (true) {
            try { done.await(); break; }
            catch (InterruptedException interruption) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        Throwable problem = failure.get();
        if (problem instanceof Error error) throw error;
        if (problem instanceof RuntimeException exception) throw exception;
    }

    public static void tickPlayer(net.minecraft.server.level.ServerPlayer player, Runnable tick) {
        if (!canParallelize()) {
            tick.run();
            return;
        }
        if (isServerExecutionThread()) {
            EntityTasks.onThread(player, () -> { tick.run(); return null; });
            return;
        }
        if (EntityTasks.hasActiveContext()) {
            EntityTasks.execute(player, tick);
            return;
        }
        EntityTasks.begin(player.serverLevel(), List.of(player), null);
        try {
            runEntitySchedule(List.of(() -> EntityTasks.run(player, tick)));
        } finally {
            EntityTasks.end();
        }
    }

    private static void checkDespawn(Entity entity) {
        if (!entity.isRemoved()) entity.checkDespawn();
    }

    private static <T> void emit(List<Runnable> tasks, List<T> items, CostModel cost, Consumer<T> action) {
        tasks.add(() -> cost.measure(items.size(), () -> items.forEach(action)));
    }

    private static <T> void addSlices(List<Runnable> tasks, List<T> items, CostModel cost, Consumer<T> action) {
        int size = cost.chunkSize(items.size());
        for (int i = 0; i < items.size(); i += size) {
            emit(tasks, items.subList(i, Math.min(i + size, items.size())), cost, action);
        }
    }

    public static <T> void forEachParallel(List<T> items, Consumer<T> action) {
        forEachParallel(items, action, GENERIC_COST);
    }

    public static <T> void forEachParallel(List<T> items, Consumer<T> action, CostModel cost) {
        if (items.isEmpty()) return;
        // A nested worker batch must not wait for another slot in its own fixed pool.
        if (!canParallelize() || isServerExecutionThread() || cost.shouldRunSequentially(items.size())) {
            cost.measure(items.size(), () -> items.forEach(action));
            return;
        }
        List<Runnable> tasks = new ArrayList<>();
        addSlices(tasks, items, cost, action);
        finishParallel(submitParallel(tasks));
    }

    private static boolean canParallelize() {
        return !isShuttingDown && !AsyncConfig.disabled.getValue() && tickPool != null && !tickPool.isShutdown();
    }

    private static ParallelBatch submitParallel(List<Runnable> tasks) {
        ParallelBatch batch = new ParallelBatch(new ConcurrentLinkedQueue<>(), new CountDownLatch(tasks.size()),
                new AtomicReference<>(), Thread.currentThread());
        for (Runnable task : tasks) {
            batch.queue().add(() -> {
                try {
                    if (batch.failure().get() == null) task.run();
                } catch (RuntimeException | Error failure) {
                    batch.failure().compareAndSet(null, failure);
                } finally {
                    batch.done().countDown();
                    LockSupport.unpark(batch.waiter());
                }
            });
        }
        int workers = Math.min(getPoolSize(), tasks.size());
        int submitted = 0;
        for (int i = 0; i < workers; i++) {
            try {
                tickPool.execute(() -> drain(batch));
                submitted++;
            } catch (RejectedExecutionException rejected) {
                break;
            }
        }
        while (submitted == 0 && !tasks.isEmpty()) {
            try {
                tickPool.execute(() -> drain(batch));
                submitted++;
            } catch (RejectedExecutionException rejected) {
                pumpMainThreadTasks(batch);
                if (tickPool.isTerminated()) {
                    drain(batch);
                    break;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(50_000);
            }
        }
        lastWorkerCount.set(submitted);
        return batch;
    }

    private static void drain(ParallelBatch batch) {
        Runnable task;
        while ((task = batch.queue().poll()) != null) {
            task.run();
            CooperativeTask.assist();
        }
    }

    private static void finishParallel(ParallelBatch batch) {
        // Workers can await owner-thread operations while holding an entity lock.
        // Running another entity here could block the only thread that services them.
        long start = System.nanoTime();
        long threshold = TimeUnit.MILLISECONDS.toNanos(Math.max(50, AsyncConfig.staleTaskTimeoutMs.getValue()));
        boolean warned = false;
        boolean interrupted = false;
        while (batch.done().getCount() > 0) {
            boolean progressed = pumpMainThreadTasks(batch);
            if (!warned && System.nanoTime() - start > threshold) {
                warned = true;
                totalTimeoutWarnings.increment();
                LOGGER.warn("Async batch exceeded {}ms; waiting for {} tasks before advancing the world",
                        AsyncConfig.staleTaskTimeoutMs.getValue(), batch.done().getCount());
            }
            if (progressed) continue;
            interrupted |= Thread.interrupted();
            if (batch.done().getCount() > 0) LockSupport.parkNanos(200_000);
        }
        Runnable callback;
        while ((callback = entityCallbacks.poll()) != null) {
            try {
                callback.run();
            } catch (RuntimeException | Error failure) {
                batch.failure().compareAndSet(null, failure);
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        Throwable failure = batch.failure().get();
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
    }

    private static boolean pumpMainThreadTasks(ParallelBatch batch) {
        try {
            return pumpMainThreadTasks();
        } catch (RuntimeException | Error failure) {
            // Even a main-thread task failure cannot allow workers into the next world phase.
            batch.failure().compareAndSet(null, failure);
            return true;
        }
    }

    public static void queueEntityCallback(Runnable callback) {
        if (isServerExecutionThread()) entityCallbacks.add(CooperativeTask.contextual(callback));
        else callback.run();
    }

    public static void queueMainThreadTask(Runnable task) {
        mainThreadTasks.add(CooperativeTask.contextual(task));
        LockSupport.unpark(serverThread);
        MinecraftServer current = server;
        if (current != null && !current.isSameThread() && !isServerExecutionThread()) {
            current.execute(ParallelProcessor::assistOwnerTasks);
        }
    }

    private static boolean pumpMainThreadTasks() {
        boolean progressed = false;
        if (server != null && server.isSameThread()) {
            Runnable task;
            while ((task = CooperativeTask.pollAllowed(mainThreadTasks)) != null) {
                task.run();
                progressed = true;
            }
            for (ServerLevel level : server.getAllLevels()) {
                progressed |= level.getChunkSource().mainThreadProcessor.pollTask();
            }
        }
        return progressed;
    }

    public static boolean shouldTickSynchronously(Entity entity) {
        return isShuttingDown || entity.level().isClientSide() || AsyncConfig.disabled.getValue();
    }

    public static void assistOwnerTasks() {
        pumpMainThreadTasks();
    }

    private static void tickEntity(ServerLevel world, Entity entity) {
        boolean recording = TickStats.isRecording();
        long start = recording ? System.nanoTime() : 0L;
        boolean async = isServerExecutionThread();
        currentEntities.incrementAndGet();
        EntityType<?> type = entity.getType();
        try {
            world.tickNonPassenger(entity);
            if (async) {
                totalAsyncTicks.increment();
                if (AsyncConfig.enableCircuitBreaker.getValue()) circuitBreaker.recordSuccess(type);
            }
        } catch (Exception failure) {
            if (!async) throw failure;
            totalAsyncFailures.increment();
            if (AsyncConfig.enableCircuitBreaker.getValue()) circuitBreaker.recordFailure(type, failure);
            LOGGER.error("Async entity tick failed for {} ({}); it is not replayed this tick", type, entity.getUUID(), failure);
        } finally {
            currentEntities.decrementAndGet();
            if (recording) {
                long elapsed = System.nanoTime() - start;
                (async ? TickStats.ASYNC_TICK_TIME_NS : TickStats.TICK_TIME_NS)
                        .computeIfAbsent(type, ignored -> new LongAdder()).add(elapsed);
                (async ? TickStats.ASYNC_TICK_COUNT : TickStats.TICK_COUNT)
                        .computeIfAbsent(type, ignored -> new LongAdder()).increment();
            }
        }
    }

    public static Object getEntityAddLock() { return ENTITY_ADD_LOCK; }
    public static MinecraftServer getServer() { return server; }
    public static void setServer(MinecraftServer newServer) {
        serverThread = Thread.currentThread();
        server = newServer;
    }

    public static boolean canBatchPlayerConnections() {
        return canParallelize() && server != null && server.isSameThread();
    }

    public static void tickPlayerConnections(List<PlayerConnectionTick> connections) {
        if (connections.isEmpty()) return;
        List<Entity> players = new ArrayList<>();
        Map<Entity, List<Runnable>> actions = new IdentityHashMap<>();
        for (PlayerConnectionTick connection : connections) {
            if (!actions.containsKey(connection.player())) players.add(connection.player());
            actions.computeIfAbsent(connection.player(), ignored -> new ArrayList<>()).add(connection.action());
        }
        // The connection phase can contain players in different dimensions; it does not claim world ticks.
        EntityTasks.begin(null, players, null);
        try {
            List<Runnable> work = new ArrayList<>();
            for (EntityTasks.Group group : EntityTasks.groups(players)) {
                work.add(() ->
                        EntityTasks.run(group.roots().get(0), () -> {
                            for (Entity player : group.roots()) actions.get(player).forEach(Runnable::run);
                        }));
            }
            runEntitySchedule(work);
        } finally {
            EntityTasks.end();
        }
    }

    public record PlayerConnectionTick(net.minecraft.server.level.ServerPlayer player, Runnable action) {}

    public static void stop() {
        if (isShuttingDown) return;
        isShuttingDown = true;
        if (tickPool != null) {
            tickPool.shutdown();
            boolean interrupted = false;
            while (!tickPool.isTerminated()) {
                if (pumpMainThreadTasks()) continue;
                try {
                    tickPool.awaitTermination(1, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            tickPool = null;
        }
        AsyncConfig.clearCaches();
        CooperativeTask.setExecutor(null);
        EntityTasks.end();
        synchronized (ParallelProcessor.class) {
            tickThreads = List.of();
        }
        entityCallbacks.clear();
        mainThreadTasks.clear();
        circuitBreaker.reset();
        totalAsyncTicks.reset();
        totalAsyncFailures.reset();
        totalTimeoutWarnings.reset();
        lastWorkerCount.set(0);
        ENTITY_TICK_COST.reset();
        DESPAWN_COST.reset();
        GENERIC_COST.reset();
        SPAWN_COST.reset();
        TickStats.resetEntityTickStats();
        server = null;
        serverThread = null;
    }

    private record ParallelBatch(ConcurrentLinkedQueue<Runnable> queue, CountDownLatch done,
                                 AtomicReference<Throwable> failure, Thread waiter) {}

    public static final class CostModel {
        private final double initialCost;
        private volatile double nanosPerItem;
        private final LongAdder nanos = new LongAdder();
        private final LongAdder items = new LongAdder();

        CostModel(double initialCost) {
            this.initialCost = initialCost;
            nanosPerItem = initialCost;
        }

        public void measure(int count, Runnable task) {
            if (count == 0) return;
            long start = System.nanoTime();
            try {
                task.run();
            } finally {
                nanos.add(System.nanoTime() - start);
                items.add(count);
            }
        }

        private synchronized void fold() {
            long count = items.sumThenReset();
            if (count > 0) {
                double sample = Math.max(100, Math.min(2_000_000, (double) nanos.sumThenReset() / count));
                nanosPerItem += (sample - nanosPerItem) * 0.25;
            }
        }

        public boolean shouldRunSequentially(int count) {
            fold();
            return count * nanosPerItem < 500_000;
        }

        public int chunkSize(int count) {
            fold();
            int workers = Math.max(1, getPoolSize());
            int fairShare = Math.max(1, (count + workers - 1) / workers);
            int byCost = Math.max(1, (int) (250_000 / nanosPerItem));
            return Math.min(fairShare, Math.min(byCost, Math.max(1, AsyncConfig.entitiesPerWorker.getValue())));
        }

        private void reset() {
            nanos.reset();
            items.reset();
            nanosPerItem = initialCost;
        }
    }
}
