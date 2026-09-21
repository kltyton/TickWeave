package com.axalotl.async.common.entity.task;

import com.axalotl.async.common.ParallelProcessor;
import com.google.common.collect.MapMaker;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** Serializes operations on one shared object without moving the caller to another thread. */
public final class SharedObjectTasks {
    private static final ConcurrentMap<Object, CooperativeTask> OWNERS = new MapMaker().weakKeys().makeMap();
    private SharedObjectTasks() {}

    private static CooperativeTask owner(Object receiver) {
        return OWNERS.computeIfAbsent(receiver, ignored -> new CooperativeTask());
    }

    public static boolean requiresOwnership(Object receiver) { return !owner(receiver).isHeld(); }

    public static Object callOwned(Object receiver, Supplier<?> action) {
        return owner(receiver).onThread(action::get, ParallelProcessor.isServerExecutionThread(),
                ParallelProcessor::assistOwnerTasks);
    }

    public static void executeOwned(Object receiver, Runnable action) {
        callOwned(receiver, () -> { action.run(); return null; });
    }
}
