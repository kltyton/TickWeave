package com.axalotl.async.forge.mixin.entity;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeHooks;
import org.spongepowered.asm.mixin.Mixin;

/** Keeps direct break-event callers on the same world-owner boundary as native mining. */
@Mixin(ForgeHooks.class)
public class BlockBreakEventMixin {
    @WrapMethod(method = "onBlockBreakEvent", remap = false)
    private static int tickweave$breakEvent(Level level, GameType type, ServerPlayer player, BlockPos position,
                                          Operation<Integer> original) {
        return EntityTasks.onMain(() -> EntityTasks.onThread(player, () -> original.call(level, type, player, position)));
    }
}
