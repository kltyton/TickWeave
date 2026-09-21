package com.axalotl.async.common.mixin.worldgen;

import com.axalotl.async.common.worldgen.NoiseInterpolationState;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin implements NoiseInterpolationState {
    @Unique private long tickweave$zRevision;
    @Unique private double tickweave$zFraction;

    @Inject(method = "updateForZ", at = @At("HEAD"))
    private void tickweave$advanceZ(int blockZ, double fraction, CallbackInfo ci) {
        tickweave$zFraction = fraction;
        tickweave$zRevision++;
    }

    @Redirect(method = "updateForZ", at = @At(value = "INVOKE",
            target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private void tickweave$deferInterpolation(List<?> interpolators, Consumer<?> update) {
        // Each interpolator evaluates this Z step only when its density is requested.
    }

    @Override
    public long tickweave$zRevision() {
        return tickweave$zRevision;
    }

    @Override
    public double tickweave$zFraction() {
        return tickweave$zFraction;
    }
}
