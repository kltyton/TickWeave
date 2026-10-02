package com.axalotl.async.common.mixin.world;

import com.axalotl.async.common.parallelised.ConcurrentCollections;
import com.axalotl.async.common.parallelised.fastutil.ConcurrentLongLinkedOpenHashSet;
import com.axalotl.async.common.entity.task.CooperativeTask;
import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Set;
import java.util.concurrent.Executor;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value={DistanceManager.class})
public abstract class DistanceManagerMixin {
    @Shadow @Final Executor mainThreadExecutor;

    @Shadow
    @Final
    @Mutable
    Set<ChunkHolder> chunksToUpdateFutures = ConcurrentCollections.newHashSet();
    @Shadow
    @Final
    @Mutable
    LongSet ticketsToRelease = new ConcurrentLongLinkedOpenHashSet();

    @Unique
    private void tickweave$onMain(Runnable action) {
        if (((BlockableEventLoop<?>) mainThreadExecutor).isSameThread()) {
            action.run();
        } else {
            EntityTasks.await(CooperativeTask.supplyAsync(() -> {
                action.run();
                return null;
            }, mainThreadExecutor));
        }
    }

    @WrapMethod(method = "addPlayer")
    private void tickweave$addPlayer(SectionPos section, ServerPlayer player, Operation<Void> original) {
        tickweave$onMain(() -> original.call(section, player));
    }

    @WrapMethod(method = "removePlayer")
    private void tickweave$removePlayer(SectionPos section, ServerPlayer player, Operation<Void> original) {
        tickweave$onMain(() -> original.call(section, player));
    }

    @WrapMethod(method = "addTicket(JLnet/minecraft/server/level/Ticket;)V")
    private void tickweave$addTicket(long chunk, Ticket<?> ticket, Operation<Void> original) {
        tickweave$onMain(() -> original.call(chunk, ticket));
    }

    @WrapMethod(method = "removeTicket(JLnet/minecraft/server/level/Ticket;)V")
    private void tickweave$removeTicket(long chunk, Ticket<?> ticket, Operation<Void> original) {
        tickweave$onMain(() -> original.call(chunk, ticket));
    }

    @WrapMethod(method = "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V")
    private void tickweave$addRegionTicket(TicketType<?> type, ChunkPos chunk, int radius, Object value,
                                           Operation<Void> original) {
        tickweave$onMain(() -> original.call(type, chunk, radius, value));
    }

    @WrapMethod(method = "removeRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V")
    private void tickweave$removeRegionTicket(TicketType<?> type, ChunkPos chunk, int radius, Object value,
                                              Operation<Void> original) {
        tickweave$onMain(() -> original.call(type, chunk, radius, value));
    }

    @WrapMethod(method = "updateChunkForced")
    private void tickweave$updateChunkForced(ChunkPos chunk, boolean forced, Operation<Void> original) {
        tickweave$onMain(() -> original.call(chunk, forced));
    }

    @WrapMethod(method = "updateSimulationDistance")
    private void tickweave$updateSimulationDistance(int distance, Operation<Void> original) {
        tickweave$onMain(() -> original.call(distance));
    }
}

