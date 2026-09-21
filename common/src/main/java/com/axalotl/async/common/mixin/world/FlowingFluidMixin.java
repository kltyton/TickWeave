package com.axalotl.async.common.mixin.world;

import com.axalotl.async.common.parallelised.utils.LastThreadLocal;
import it.unimi.dsi.fastutil.objects.Object2ByteLinkedOpenHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @Shadow @Final @Mutable
    private static ThreadLocal<Object2ByteLinkedOpenHashMap<Block.BlockStatePairKey>> OCCLUSION_CACHE;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void tickweave$cacheThreadLookup(CallbackInfo ci) {
        if (OCCLUSION_CACHE.getClass() == ThreadLocal.withInitial(() -> null).getClass()) {
            OCCLUSION_CACHE = new LastThreadLocal<>(OCCLUSION_CACHE);
        }
    }
}
