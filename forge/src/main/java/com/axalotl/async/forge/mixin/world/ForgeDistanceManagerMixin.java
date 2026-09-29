package com.axalotl.async.forge.mixin.world;

import com.axalotl.async.common.entity.task.CooperativeTask;
import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.Executor;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Routes Forge's forced tickets through the same native main-thread queue. */
@Mixin(DistanceManager.class)
public abstract class ForgeDistanceManagerMixin {
    @Shadow @Final Executor mainThreadExecutor;

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

    @WrapMethod(method = "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;Z)V", remap = false)
    private void tickweave$addRegionTicket(TicketType<?> type, ChunkPos chunk, int radius, Object value,
                                           boolean forceTicks, Operation<Void> original) {
        tickweave$onMain(() -> original.call(type, chunk, radius, value, forceTicks));
    }

    @WrapMethod(method = "removeRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;Z)V", remap = false)
    private void tickweave$removeRegionTicket(TicketType<?> type, ChunkPos chunk, int radius, Object value,
                                              boolean forceTicks, Operation<Void> original) {
        tickweave$onMain(() -> original.call(type, chunk, radius, value, forceTicks));
    }
}
