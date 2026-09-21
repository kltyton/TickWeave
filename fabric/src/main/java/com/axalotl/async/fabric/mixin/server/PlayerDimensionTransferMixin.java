package com.axalotl.async.fabric.mixin.server;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** Serializes the player protocol and world-membership transition on Fabric. */
@Mixin(ServerPlayer.class)
public class PlayerDimensionTransferMixin {
    @WrapMethod(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/world/entity/Entity;")
    private Entity tickweave$changeDimension(ServerLevel world, Operation<Entity> original) {
        return EntityTasks.onMain(() -> original.call(world));
    }
}
