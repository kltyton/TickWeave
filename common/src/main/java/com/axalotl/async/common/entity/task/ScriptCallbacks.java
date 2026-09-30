package com.axalotl.async.common.entity.task;

import com.axalotl.async.common.ParallelProcessor;
import java.util.function.Supplier;

/** Keeps a script invocation and its entity callbacks on the server thread. */
public final class ScriptCallbacks {
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private ScriptCallbacks() {}

    public static boolean isRunning() { return DEPTH.get()[0] != 0; }

    public static boolean requiresDispatch() {
        var server = ParallelProcessor.getServer();
        return server != null && (ParallelProcessor.isServerExecutionThread()
                || server.isSameThread() && !isRunning());
    }

    public static Object call(Supplier<Object> action) {
        var server = ParallelProcessor.getServer();
        if (server == null || server.isSameThread()) return invoke(action);
        return EntityTasks.await(CooperativeTask.supplyAsync(() -> invoke(action),
                callback -> ParallelProcessor.queueMainThreadTask(new Pending(callback))));
    }

    private static Object invoke(Supplier<Object> action) {
        int[] depth = DEPTH.get();
        depth[0]++;
        try { return action.get(); }
        finally { depth[0]--; }
    }

    static boolean isPending(Runnable action) { return action instanceof Pending; }

    private record Pending(Runnable action) implements Runnable {
        @Override public void run() { action.run(); }
    }
}
