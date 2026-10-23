package com.axalotl.async.common.mixin.block;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** Keeps bubble-column particles and their world random draws on the world thread. */
@Mixin(BubbleColumnBlock.class)
public final class BubbleColumnBlockMixin {
    @WrapMethod(method = "entityInside")
    private void tickweave$worldContact(BlockState state, Level level, BlockPos pos, Entity entity,
                                       Operation<Void> original) {
        EntityTasks.onMain(entity, () -> EntityTasks.call(entity, () -> {
            original.call(state, level, pos, entity);
            return null;
        }));
    }
}
