package com.axalotl.async.common.mixin.worldgen;

import com.axalotl.async.common.worldgen.NoiseInterpolationState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class NoiseInterpolatorMixin {
    @Shadow private double valueZ0;
    @Shadow private double valueZ1;
    @Shadow private double value;
    @Unique private NoiseInterpolationState tickweave$owner;
    @Unique private long tickweave$zRevision;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void tickweave$rememberOwner(NoiseChunk owner, DensityFunction filler, CallbackInfo ci) {
        tickweave$owner = (NoiseInterpolationState) owner;
        tickweave$zRevision = tickweave$owner.tickweave$zRevision();
    }

    @Inject(method = "compute", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;value:D"))
    private void tickweave$readInterpolatedValue(DensityFunction.FunctionContext context,
                                                CallbackInfoReturnable<Double> cir) {
        tickweave$resolveZ();
    }

    @Inject(method = "updateForX", at = @At("HEAD"))
    private void tickweave$preservePreviousValue(double fraction, CallbackInfo ci) {
        // Vanilla retains the last Z value until updateForZ, even after the X coefficients change.
        tickweave$resolveZ();
    }

    @Inject(method = "updateForZ", at = @At("RETURN"))
    private void tickweave$recordExplicitUpdate(double fraction, CallbackInfo ci) {
        tickweave$zRevision = tickweave$owner.tickweave$zRevision();
    }

    @Unique
    private void tickweave$resolveZ() {
        long revision = tickweave$owner.tickweave$zRevision();
        if (tickweave$zRevision != revision) {
            value = Mth.lerp(tickweave$owner.tickweave$zFraction(), valueZ0, valueZ1);
            tickweave$zRevision = revision;
        }
    }
}
