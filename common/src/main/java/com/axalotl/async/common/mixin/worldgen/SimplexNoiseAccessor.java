package com.axalotl.async.common.mixin.worldgen;

import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SimplexNoise.class)
public interface SimplexNoiseAccessor {
    @Accessor("GRADIENT")
    static int[][] tickweave$gradients() {
        throw new AssertionError();
    }
}
