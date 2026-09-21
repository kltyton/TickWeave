package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** Transfers world membership on the owner thread while the source worker services entity requests. */
@Mixin(Entity.class)
public class DimensionTransferMixin {
    @WrapMethod(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/world/entity/Entity;")
    private Entity tickweave$changeDimension(ServerLevel world, Operation<Entity> original) {
        return EntityTasks.onMain((Entity) (Object) this, () -> original.call(world));
    }
}
