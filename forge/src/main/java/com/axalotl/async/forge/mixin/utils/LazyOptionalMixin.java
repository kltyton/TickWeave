package com.axalotl.async.forge.mixin.utils;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.util.NonNullConsumer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Allocates listener storage only when a valid optional receives its first listener. */
@Mixin(value = LazyOptional.class, remap = false)
public abstract class LazyOptionalMixin<T> {
    @Shadow @Final
    private Object lock;
    @Shadow @Final @Mutable
    private Set<NonNullConsumer<LazyOptional<T>>> listeners;

    @ModifyExpressionValue(method = "addListener", at = @At(value = "FIELD",
            target = "Lnet/minecraftforge/common/util/LazyOptional;listeners:Ljava/util/Set;"))
    private Set<NonNullConsumer<LazyOptional<T>>> tickweave$initializeListeners(
            Set<NonNullConsumer<LazyOptional<T>>> original) {
        synchronized (lock) {
            if (original != null) return original;
            if (listeners == null) listeners = new HashSet<>();
            return listeners;
        }
    }

    @ModifyExpressionValue(method = {"removeListener", "invalidate"}, at = @At(value = "FIELD",
            target = "Lnet/minecraftforge/common/util/LazyOptional;listeners:Ljava/util/Set;"))
    private Set<NonNullConsumer<LazyOptional<T>>> tickweave$readListeners(
            Set<NonNullConsumer<LazyOptional<T>>> original) {
        synchronized (lock) {
            if (original != null) return original;
            return listeners == null ? Collections.emptySet() : listeners;
        }
    }
}
