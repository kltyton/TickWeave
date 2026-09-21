package com.axalotl.async.common.entity.task;

import com.axalotl.async.common.ParallelProcessor;
import net.minecraft.network.PacketListener;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Keeps packet thread contracts while sharing player ownership with connection ticks. */
public final class PlayerNetworkTasks {
    private static final ThreadLocal<ServerGamePacketListenerImpl> RECEIVER = new ThreadLocal<>();

    private PlayerNetworkTasks() {}

    public static void receive(PacketListener listener, Runnable action) {
        if (!(listener instanceof ServerGamePacketListenerImpl playerListener)) {
            action.run();
            return;
        }
        ServerGamePacketListenerImpl previous = RECEIVER.get();
        RECEIVER.set(playerListener);
        try {
            var server = ParallelProcessor.getServer();
            if (server != null && server.isSameThread()) {
                onConnection(playerListener.connection, () ->
                        EntityTasks.onThread(playerListener.player, () -> { action.run(); return null; }));
            } else {
                // Netty must remain free to complete sends/close requests awaited by the tick thread.
                action.run();
            }
        } finally {
            if (previous == null) RECEIVER.remove();
            else RECEIVER.set(previous);
        }
    }

    public static void onConnection(Connection connection, Runnable action) {
        ((ConnectionTaskAccess) connection).tickweave$connectionTask().onThread(
                () -> { action.run(); return null; }, ParallelProcessor.isServerExecutionThread(),
                ParallelProcessor::assistOwnerTasks);
    }

    public static Runnable capture(Runnable action) {
        ServerGamePacketListenerImpl listener = RECEIVER.get();
        // Resolve the player and batch resource on execution, including after respawn.
        return listener == null ? action : () -> receive(listener, action);
    }
}
