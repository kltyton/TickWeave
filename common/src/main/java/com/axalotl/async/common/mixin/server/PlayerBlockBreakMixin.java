package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Delayed mining uses the world owner and retains the player's execution resource. */
@Mixin(ServerPlayerGameMode.class)
public abstract class PlayerBlockBreakMixin {
    @Shadow @Final protected ServerPlayer player;

    @WrapMethod(method = "destroyBlock")
    private boolean tickweave$destroyBlock(BlockPos position, Operation<Boolean> original) {
        return EntityTasks.onMain(() -> EntityTasks.onThread(player, () -> original.call(position)));
    }
}
