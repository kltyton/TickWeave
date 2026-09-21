package com.axalotl.async.common.callback;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public final class DeferredCallback {
    private DeferredCallback() {
    }

    public static CallbackInfoReturnable<?> materialize(CallbackInfoReturnable<?> callback, String id) {
        return callback != null ? callback : new CallbackInfoReturnable<>(id, true);
    }
}
