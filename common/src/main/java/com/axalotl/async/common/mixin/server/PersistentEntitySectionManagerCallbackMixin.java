package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.ParallelProcessor;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Commits tracking changes on the server thread before the batch barrier returns. */
@Mixin(PersistentEntitySectionManager.Callback.class)
public abstract class PersistentEntitySectionManagerCallbackMixin {
    @Unique private final AtomicBoolean async$moveQueued = new AtomicBoolean();
    @Unique private final AtomicBoolean async$removed = new AtomicBoolean();

    @WrapMethod(method = "onMove")
    private void async$onMove(Operation<Void> original) {
        if (async$moveQueued.compareAndSet(false, true)) {
            ParallelProcessor.queueEntityCallback(() -> {
                async$moveQueued.set(false);
                if (!async$removed.get()) original.call();
            });
        }
    }

    @WrapMethod(method = "onRemove")
    private void async$onRemove(Entity.RemovalReason reason, Operation<Void> original) {
        ParallelProcessor.queueEntityCallback(() -> {
            if (async$removed.compareAndSet(false, true)) {
                original.call(reason);
            }
        });
    }
}
