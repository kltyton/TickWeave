package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.entity.task.EntityTasks;
import com.axalotl.async.common.entity.task.PlayerNetworkTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Runs complete game connections as one entity batch after native connection-list traversal. */
@Mixin(ServerConnectionListener.class)
public class PlayerConnectionBatchMixin {
    @Unique private List<ParallelProcessor.PlayerConnectionTick> tickweave$connections;

    @WrapMethod(method = "tick")
    private void tickweave$batchConnections(Operation<Void> original) {
        if (!ParallelProcessor.canBatchPlayerConnections()) {
            original.call();
            return;
        }
        if (tickweave$connections != null) throw new IllegalStateException("Nested connection tick");
        List<ParallelProcessor.PlayerConnectionTick> pending = new ArrayList<>();
        tickweave$connections = pending;
        try {
            original.call();
        } finally {
            tickweave$connections = null;
        }
        if (!pending.isEmpty()) ParallelProcessor.tickPlayerConnections(pending);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;tick()V"))
    private void tickweave$collectConnection(Connection connection, Operation<Void> original) {
        if (tickweave$connections == null || !(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)) {
            original.call(connection);
            return;
        }
        tickweave$connections.add(new ParallelProcessor.PlayerConnectionTick(listener.player, () -> {
            try {
                PlayerNetworkTasks.onConnection(connection, () ->
                        EntityTasks.onThread(listener.player, () -> { original.call(connection); return null; }));
            } catch (Exception failure) {
                // Preserve ServerConnectionListener's native distinction between local crashes and remote disconnects.
                if (connection.isMemoryConnection()) {
                    throw new ReportedException(CrashReport.forThrowable(failure, "Ticking memory connection"));
                }
                EntityTasks.onMain(() -> {
                    ParallelProcessor.LOGGER.warn("Failed to handle packet for {}", connection.getRemoteAddress(), failure);
                    Component reason = Component.literal("Internal server error");
                    connection.send(new ClientboundDisconnectPacket(reason),
                            PacketSendListener.thenRun(() -> connection.disconnect(reason)));
                    connection.setReadOnly();
                    return null;
                });
            }
        }));
    }
}
