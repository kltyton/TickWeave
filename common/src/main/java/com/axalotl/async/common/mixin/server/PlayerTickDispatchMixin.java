package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Completes player simulation before the network listener restores its authoritative position. */
@Mixin(ServerGamePacketListenerImpl.class)
public class PlayerTickDispatchMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;doTick()V"))
    private void tickweave$playerTick(ServerPlayer player, Operation<Void> original) {
        ParallelProcessor.tickPlayer(player, () -> original.call(player));
    }

    @WrapMethod(method = "onDisconnect")
    private void tickweave$disconnectOnMain(Component reason, Operation<Void> original) {
        EntityTasks.onMain(() -> {
            original.call(reason);
            return null;
        });
    }
}
