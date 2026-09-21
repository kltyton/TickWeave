/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
 *  com.llamalad7.mixinextras.injector.wrapoperation.Operation
 *  net.minecraft.world.level.entity.EntityAccess
 *  net.minecraft.world.level.entity.PersistentEntitySectionManager
 *  net.minecraft.world.level.entity.Visibility
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Unique
 */
package com.axalotl.async.common.mixin.server;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.axalotl.async.common.parallelised.fastutil.Long2ObjectConcurrentHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.objectweb.asm.Opcodes;

@Mixin(value={PersistentEntitySectionManager.class})
public abstract class PersistentEntitySectionManagerMixin
implements AutoCloseable {
    @Shadow @Final @Mutable
    private Long2ObjectMap<Visibility> chunkVisibility;

    @WrapOperation(method = "<init>", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;chunkVisibility:Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;",
            opcode = Opcodes.PUTFIELD))
    private void tickweave$visibilityStorage(PersistentEntitySectionManager<?> instance,
                                             Long2ObjectMap<Visibility> initial, Operation<Void> original) {
        Long2ObjectMap<Visibility> concurrent = new Long2ObjectConcurrentHashMap<>();
        concurrent.defaultReturnValue(initial.defaultReturnValue());
        concurrent.putAll(initial);
        // Install before EntitySectionStorage captures the map and the native default is assigned.
        original.call(instance, concurrent);
    }

    @WrapMethod(method={"getEffectiveStatus"})
    private static <T extends EntityAccess> Visibility getEffectiveStatus(T entity, Visibility visibility, Operation<Visibility> original) {
        Visibility result = (Visibility)original.call(new Object[]{entity, visibility});
        if (result == null) {
            return entity.isAlwaysTicking() ? Visibility.TICKING : Visibility.TRACKED;
        }
        return result;
    }
}

