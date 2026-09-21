package com.axalotl.async.common.mixin.world;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SectionStorage.class)
public abstract class SectionStorageMixin<R> {

    @WrapMethod(method = {"get", "getOrLoad"})
    private Optional<R> tickweave$get(long section, Operation<Optional<R>> original) {
        return EntityTasks.onMain(() -> original.call(section));
    }

    @WrapMethod(method = {"getOrCreate"})
    private R tickweave$getOrCreate(long section, Operation<R> original) {
        return EntityTasks.onMain(() -> original.call(section));
    }

    @WrapMethod(method = {"setDirty"})
    private void tickweave$setDirty(long section, Operation<Void> original) {
        EntityTasks.onMain(() -> { original.call(section); return null; });
    }

    @WrapMethod(method = {"tick"})
    private void tickweave$tick(BooleanSupplier aheadOfTime, Operation<Void> original) {
        EntityTasks.onMain(() -> { original.call(aheadOfTime); return null; });
    }

    @WrapMethod(method = {"hasWork"})
    private boolean tickweave$hasWork(Operation<Boolean> original) {
        return EntityTasks.onMain(() -> original.call());
    }

    @WrapMethod(method = {"flush"})
    private void tickweave$flush(ChunkPos pos, Operation<Void> original) {
        EntityTasks.onMain(() -> { original.call(pos); return null; });
    }

    @WrapMethod(method = {"close"})
    private void tickweave$close(Operation<Void> original) {
        EntityTasks.onMain(() -> { original.call(); return null; });
    }
}
