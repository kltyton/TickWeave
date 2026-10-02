package com.axalotl.async.forge.mixin.world;

import com.axalotl.async.forge.world.LightingBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Avoids promoting a chunk to FULL from its own lighting callback. */
@Mixin(Level.class)
public class LightingBlockEntityLookupMixin {
    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void tickweave$lightingEntity(BlockPos position, CallbackInfoReturnable<BlockEntity> cir) {
        Level world = (Level) (Object) this;
        if (LightingBlockEntities.appliesTo(world)) {
            cir.setReturnValue(LightingBlockEntities.getBlockEntity(world, position));
        }
    }
}
