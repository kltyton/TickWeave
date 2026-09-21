package com.axalotl.async.common.mixin.utils;

import java.util.concurrent.atomic.AtomicLong;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Uses the vanilla LCG with retrying atomic updates and protects the Gaussian cache. */
@Mixin(LegacyRandomSource.class)
public abstract class LegacyRandomSourceMixin {
    @Shadow @Final private AtomicLong seed;
    @Shadow @Final private MarsagliaPolarGaussian gaussianSource;

    @Inject(method = "next", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/ThreadingDetector;makeThreadingException(Ljava/lang/String;Ljava/lang/Thread;)Lnet/minecraft/ReportedException;"), cancellable = true)
    private void tickweave$retryTransition(int bits, CallbackInfoReturnable<Integer> cir) {
        long previous;
        long next;
        do {
            previous = seed.get();
            next = (previous * 25214903917L + 11L) & 0xffffffffffffL;
        } while (!seed.compareAndSet(previous, next));
        cir.setReturnValue((int) (next >>> (48 - bits)));
    }

    @WrapMethod(method = "setSeed")
    private void tickweave$guardReseed(long value, Operation<Void> original) {
        synchronized (gaussianSource) {
            original.call(value);
        }
    }

    @WrapOperation(method = "setSeed", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/atomic/AtomicLong;compareAndSet(JJ)Z"))
    private boolean tickweave$publishSeed(AtomicLong target, long previous, long value, Operation<Boolean> original) {
        target.set(value);
        return true;
    }

    @WrapMethod(method = "nextGaussian")
    private double tickweave$guardGaussian(Operation<Double> original) {
        synchronized (gaussianSource) { return original.call(); }
    }
}
