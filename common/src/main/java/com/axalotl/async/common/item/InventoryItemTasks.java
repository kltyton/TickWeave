package com.axalotl.async.common.item;

import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.entity.task.EntityTasks;

/** Runs inventory callbacks with explicit server-thread requirements through the owner queue. */
public final class InventoryItemTasks {
    private InventoryItemTasks() {}

    public static boolean requiresOwnership(Object item) {
        var server = ParallelProcessor.getServer();
        return server != null && !server.isSameThread() && ParallelProcessor.isServerExecutionThread();
    }

    public static void executeOwned(Object item, Runnable callback) {
        EntityTasks.onMain(() -> { callback.run(); return null; });
    }
}
