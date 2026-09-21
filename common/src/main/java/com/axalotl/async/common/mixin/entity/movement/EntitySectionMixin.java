/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
 *  com.llamalad7.mixinextras.injector.wrapoperation.Operation
 *  net.minecraft.util.ClassInstanceMultiMap
 *  net.minecraft.world.level.entity.EntityAccess
 *  net.minecraft.world.level.entity.EntitySection
 *  net.minecraft.world.level.entity.Visibility
 *  org.spongepowered.asm.mixin.Final
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Overwrite
 *  org.spongepowered.asm.mixin.Shadow
 *  org.spongepowered.asm.mixin.Unique
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.axalotl.async.common.mixin.entity.movement;

import com.axalotl.async.common.entity.query.CollisionClassFilter;
import com.axalotl.async.common.entity.query.CollisionQuery;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes stable query membership without holding a section lock across entity callbacks. */
@Mixin(value={EntitySection.class})
public class EntitySectionMixin<T extends EntityAccess> {
    @Shadow
    @Final
    private ClassInstanceMultiMap<T> storage;
    @Shadow
    private Visibility chunkStatus;
    @Unique
    private final AtomicReference<Visibility> async$atomicStatus = new AtomicReference<Visibility>(Visibility.HIDDEN);
    @Unique
    private List<T> async$entitySnapshot;
    @Unique
    private List<T> tickweave$collisionSnapshot;
    @Unique
    private final Map<Class<?>, Collection<?>> async$typeSnapshots = new IdentityHashMap<>();

    @Inject(method={"<init>"}, at={@At(value="TAIL")})
    private void async$init(Class<?> clazz, Visibility status, CallbackInfo ci) {
        this.async$atomicStatus.set(status != null ? status : Visibility.HIDDEN);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Overwrite
    public void add(T entity) {
        synchronized (this.storage) {
            this.storage.add(entity);
            this.async$invalidateSnapshots();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Overwrite
    public boolean remove(T entity) {
        synchronized (this.storage) {
            boolean removed = this.storage.remove(entity);
            if (removed) this.async$invalidateSnapshots();
            return removed;
        }
    }

    @Overwrite
    public Visibility getStatus() {
        return this.async$atomicStatus.get();
    }

    @Overwrite
    public Visibility updateChunkStatus(Visibility status) {
        Visibility safeStatus = status != null ? status : Visibility.HIDDEN;
        Visibility old = this.async$atomicStatus.getAndSet(safeStatus);
        this.chunkStatus = safeStatus;
        return old != null ? old : Visibility.HIDDEN;
    }

    @WrapMethod(method={"getEntities()Ljava/util/stream/Stream;"})
    private Stream<T> getEntities(Operation<Stream<T>> original) {
        return this.async$snapshotEntities().stream();
    }

    @WrapOperation(method="getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;", at=@At(value="INVOKE", target="Lnet/minecraft/util/ClassInstanceMultiMap;iterator()Ljava/util/Iterator;"))
    private Iterator<T> async$queryIterator(ClassInstanceMultiMap<T> storage, Operation<Iterator<T>> original) {
        return this.async$snapshotEntities().iterator();
    }

    @WrapOperation(method="getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;", at=@At(value="INVOKE", target="Lnet/minecraft/util/ClassInstanceMultiMap;find(Ljava/lang/Class;)Ljava/util/Collection;"))
    @SuppressWarnings("unchecked")
    private <S> Collection<S> async$queryType(ClassInstanceMultiMap<T> storage, Class<S> type,
                                             Operation<Collection<S>> original,
                                             @Local(argsOnly = true) EntityTypeTest<?, ?> filter) {
        synchronized (storage) {
            if (filter == CollisionQuery.TYPE) {
                if (this.tickweave$collisionSnapshot == null) {
                    List<T> candidates = new ArrayList<>();
                    for (T entity : this.async$snapshotEntities()) {
                        // Vanilla/Fabric Level expands a dragon into its parts inside the original consumer.
                        if (entity instanceof EnderDragon || CollisionClassFilter.mayCollide(entity.getClass())) candidates.add(entity);
                    }
                    this.tickweave$collisionSnapshot = List.copyOf(candidates);
                }
                return (Collection<S>) this.tickweave$collisionSnapshot;
            }
            // The class key binds the cached collection's element type; native find keeps its live-view contract.
            return (Collection<S>) this.async$typeSnapshots.computeIfAbsent(type,
                    key -> List.copyOf(original.call(storage, type)));
        }
    }

    @Unique
    private List<T> async$snapshotEntities() {
        synchronized (this.storage) {
            if (this.async$entitySnapshot == null) this.async$entitySnapshot = this.storage.getAllInstances();
            return this.async$entitySnapshot;
        }
    }

    @Unique
    private void async$invalidateSnapshots() {
        this.async$entitySnapshot = null;
        this.tickweave$collisionSnapshot = null;
        this.async$typeSnapshots.clear();
    }
}
