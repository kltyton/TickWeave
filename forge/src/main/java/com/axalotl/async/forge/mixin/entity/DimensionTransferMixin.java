package com.axalotl.async.forge.mixin.entity;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.util.ITeleporter;
import org.spongepowered.asm.mixin.Mixin;

/** Covers Forge's teleporter overload and ServerPlayer override. */
@Mixin({Entity.class, ServerPlayer.class})
public class DimensionTransferMixin {
    @WrapMethod(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraftforge/common/util/ITeleporter;)Lnet/minecraft/world/entity/Entity;", remap = false)
    private Entity tickweave$changeDimension(ServerLevel world, ITeleporter teleporter, Operation<Entity> original) {
        return EntityTasks.onMain((Entity) (Object) this, () -> original.call(world, teleporter));
    }
}
