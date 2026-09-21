package com.axalotl.async.common.mixin.worldgen;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ImprovedNoise.class)
public abstract class ImprovedNoiseMixin {
    @Shadow @Final private byte[] p;
    @Unique private final double[] tickweave$gradients = new double[256 * 4];

    @Inject(method = "<init>", at = @At("RETURN"))
    private void tickweave$expandGradients(RandomSource random, CallbackInfo ci) {
        int[][] source = SimplexNoiseAccessor.tickweave$gradients();
        double[] gradients = tickweave$gradients;
        for (int index = 0; index < 256; index++) {
            int[] gradient = source[p[index] & 15];
            int offset = index << 2;
            gradients[offset] = gradient[0];
            gradients[offset + 1] = gradient[1];
            gradients[offset + 2] = gradient[2];
        }
    }

    /**
     * @author TickWeave contributors
     * @reason Resolve the final permutation and gradient conversion once per noise instance.
     */
    @Overwrite
    private double sampleAndLerp(int cellX, int cellY, int cellZ,
                                 double x, double y, double z, double fadeY) {
        int px0 = p[cellX & 255] & 255;
        int px1 = p[(cellX + 1) & 255] & 255;
        int p00 = (p[(px0 + cellY) & 255] & 255) + cellZ;
        int p01 = (p[(px0 + cellY + 1) & 255] & 255) + cellZ;
        int p10 = (p[(px1 + cellY) & 255] & 255) + cellZ;
        int p11 = (p[(px1 + cellY + 1) & 255] & 255) + cellZ;
        double n000 = tickweave$dot(p00, x, y, z);
        double n100 = tickweave$dot(p10, x - 1.0, y, z);
        double n010 = tickweave$dot(p01, x, y - 1.0, z);
        double n110 = tickweave$dot(p11, x - 1.0, y - 1.0, z);
        double n001 = tickweave$dot(p00 + 1, x, y, z - 1.0);
        double n101 = tickweave$dot(p10 + 1, x - 1.0, y, z - 1.0);
        double n011 = tickweave$dot(p01 + 1, x, y - 1.0, z - 1.0);
        double n111 = tickweave$dot(p11 + 1, x - 1.0, y - 1.0, z - 1.0);
        return Mth.lerp3(Mth.smoothstep(x), Mth.smoothstep(fadeY), Mth.smoothstep(z),
                n000, n100, n010, n110, n001, n101, n011, n111);
    }

    @Unique
    private double tickweave$dot(int permutationIndex, double x, double y, double z) {
        int offset = (permutationIndex & 255) << 2;
        double[] gradients = tickweave$gradients;
        return gradients[offset] * x + gradients[offset + 1] * y + gradients[offset + 2] * z;
    }
}
