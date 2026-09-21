/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.llamalad7.mixinextras.sugar.Local
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.level.Explosion
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.LevelAccessor
 *  org.spongepowered.asm.mixin.Final
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Shadow
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Redirect
 */
package com.axalotl.async.common.mixin.world;

import com.axalotl.async.common.entity.query.CollisionQuery;
import com.axalotl.async.common.entity.query.CollisionClassFilter;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = { Level.class }, priority = 1500)
public abstract class LevelMixin
        implements LevelAccessor,
        AutoCloseable {
    @Shadow
    @Final
    private Thread thread;

    @Redirect(method = {
            "getBlockEntity" }, at = @At(value = "INVOKE", target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"))
    private Thread overwriteCurrentThread() {
        return this.thread;
    }

    @WrapOperation(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/LevelEntityGetter;get(Lnet/minecraft/world/phys/AABB;Ljava/util/function/Consumer;)V"))
    private void tickweave$queryCollisionCandidates(LevelEntityGetter<Entity> getter, AABB box,
                                                    Consumer<Entity> consumer, Operation<Void> original,
                                                    @Local(argsOnly = true) Predicate<? super Entity> predicate) {
        if (predicate instanceof CollisionQuery.Filter
                || predicate == EntitySelector.CAN_BE_COLLIDED_WITH && CollisionClassFilter.canFilter(null)) {
            getter.get(CollisionQuery.TYPE, box, AbortableIterationConsumer.forConsumer(consumer));
        } else {
            original.call(getter, box, consumer);
        }
    }

}
