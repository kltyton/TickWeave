package com.axalotl.async.common.diagnostics.thread;

import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;

/** Formats the complete stack and lock data already captured by the watchdog. */
public final class ThreadDump {
    private ThreadDump() {}

    public static String format(ThreadInfo[] snapshot) {
        StringBuilder output = new StringBuilder();
        for (ThreadInfo thread : snapshot) {
            output.append('"').append(thread.getThreadName()).append("\" Id=")
                    .append(thread.getThreadId()).append(' ').append(thread.getThreadState()).append('\n');
            if (thread.getLockInfo() != null) {
                output.append("\twaiting on ").append(thread.getLockInfo());
                if (thread.getLockOwnerId() != -1) output.append(" owned by \"")
                        .append(thread.getLockOwnerName()).append("\" Id=").append(thread.getLockOwnerId());
                output.append('\n');
            }
            StackTraceElement[] stack = thread.getStackTrace();
            MonitorInfo[] monitors = thread.getLockedMonitors();
            for (int depth = 0; depth < stack.length; depth++) {
                output.append("\tat ").append(stack[depth]).append('\n');
                for (MonitorInfo monitor : monitors) if (monitor.getLockedStackDepth() == depth)
                    output.append("\t- locked ").append(monitor).append('\n');
            }
            for (LockInfo lock : thread.getLockedSynchronizers()) output.append("\t- locked synchronizer ")
                    .append(lock).append('\n');
            output.append('\n');
        }
        return output.toString();
    }
}
