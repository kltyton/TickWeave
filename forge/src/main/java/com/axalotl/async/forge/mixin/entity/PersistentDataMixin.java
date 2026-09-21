package com.axalotl.async.forge.mixin.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Publishes one persistent-data root when concurrent callers initialize a Forge entity. */
@Mixin(Entity.class)
public abstract class PersistentDataMixin {
    @Shadow(remap = false) private CompoundTag persistentData;
    @Unique private static final AtomicReferenceFieldUpdater<Entity, CompoundTag> tickweave$persistentDataPublisher =
            AtomicReferenceFieldUpdater.newUpdater(Entity.class, CompoundTag.class, "persistentData");

    @ModifyExpressionValue(method = "getPersistentData", remap = false,
            at = @At(value = "NEW", target = "()Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag tickweave$initializePersistentData(CompoundTag created) {
        // The native assignment must receive the winning root, including after a competing initialization.
        tickweave$persistentDataPublisher.compareAndSet((Entity) (Object) this, null, created);
        return persistentData;
    }
}
