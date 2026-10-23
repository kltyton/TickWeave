package com.axalotl.async.common.mixin.worldgen;

import com.axalotl.async.common.bootstrap.worldgen.NoiseInterpolationExtension;
import com.axalotl.async.common.worldgen.NoiseInterpolationState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Publishes lazy Z steps without depending on the interpolator loop's implementation. */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin implements NoiseInterpolationState {
    @Shadow @Final private List<NoiseChunk.NoiseInterpolator> interpolators;
    @Unique private long tickweave$zRevision;
    @Unique private double tickweave$zFraction;
    @Unique private boolean tickweave$customInterpolators;

    @Invoker("updateForZ")
    public abstract void tickweave$invokeUpdateForZ(int blockZ, double fraction);

    @Override
    public boolean tickweave$deferZ(double fraction, String updateMethod) {
        if (tickweave$customInterpolators || interpolators.getClass() != ArrayList.class
                || !NoiseInterpolationExtension.isPureZ(NoiseChunk.NoiseInterpolator.class, updateMethod)) {
            return false;
        }
        tickweave$zFraction = fraction;
        tickweave$zRevision++;
        return true;
    }

    @Override
    public void tickweave$markCustomInterpolator() {
        tickweave$customInterpolators = true;
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
