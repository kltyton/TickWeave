/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
 *  com.llamalad7.mixinextras.injector.wrapoperation.Operation
 *  it.unimi.dsi.fastutil.longs.Long2ObjectMap
 *  it.unimi.dsi.fastutil.longs.LongSortedSet
 *  net.minecraft.world.level.entity.EntityAccess
 *  net.minecraft.world.level.entity.EntitySection
 *  net.minecraft.world.level.entity.EntitySectionStorage
 *  org.spongepowered.asm.mixin.Final
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Mutable
 *  org.spongepowered.asm.mixin.Shadow
 *  org.spongepowered.asm.mixin.Unique
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.axalotl.async.common.mixin.entity.movement;

import com.axalotl.async.common.parallelised.fastutil.ConcurrentLongSortedSet;
import com.axalotl.async.common.parallelised.fastutil.Long2ObjectConcurrentHashMap;
import com.axalotl.async.common.entity.query.SectionQueryIndex;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import java.util.Objects;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={EntitySectionStorage.class})
public abstract class EntitySectionStorageMixin<T extends EntityAccess> {
    @Shadow
    @Final
    @Mutable
    private Long2ObjectMap<EntitySection<T>> sections;
    @Shadow
    @Final
    @Mutable
    private LongSortedSet sectionIds;
    @Unique
    private final Object async$createLock = new Object();
    @Unique
    private volatile SectionQueryIndex<T> async$queryIndex;

    @Shadow
    public abstract LongStream getExistingSectionPositionsInChunk(long var1);

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @WrapMethod(method={"getOrCreateSection"})
    private EntitySection<T> getOrCreateSection(long pos, Operation<EntitySection<T>> original) {
        EntitySection existing = (EntitySection)this.sections.get(pos);
        if (existing != null) {
            return existing;
        }
        Object object = this.async$createLock;
        synchronized (object) {
            existing = (EntitySection)this.sections.get(pos);
            if (existing != null) {
                return existing;
            }
            this.async$queryIndex = null;
            try {
                return original.call(pos);
            } finally {
                this.async$queryIndex = null;
            }
        }
    }

    @WrapMethod(method="remove")
    private void async$removeSection(long position, Operation<Void> original) {
        synchronized (this.async$createLock) {
            this.async$queryIndex = null;
            try {
                original.call(position);
            } finally {
                this.async$queryIndex = null;
            }
        }
    }

    @Unique
    private SectionQueryIndex<T> async$queryIndex() {
        SectionQueryIndex<T> snapshot = this.async$queryIndex;
        if (snapshot != null) return snapshot;
        synchronized (this.async$createLock) {
            snapshot = this.async$queryIndex;
            if (snapshot == null) {
                snapshot = new SectionQueryIndex<>(this.sectionIds, this.sections);
                this.async$queryIndex = snapshot;
            }
            return snapshot;
        }
    }

    @Inject(method={"<init>"}, at={@At(value="TAIL")})
    private void replaceCollections(CallbackInfo ci) {
        this.sections = new Long2ObjectConcurrentHashMap<EntitySection<T>>();
        this.sectionIds = new ConcurrentLongSortedSet();
    }

    @WrapMethod(method={"getExistingSectionsInChunk"})
    private Stream<EntitySection<T>> getExistingSections(long pos, Operation<Stream<EntitySection<T>>> original) {
        return this.getExistingSectionPositionsInChunk(pos).mapToObj(arg_0 -> this.sections.get(arg_0)).filter(Objects::nonNull).toList().stream();
    }

    @WrapMethod(method = "forEachAccessibleNonEmptySection")
    private void async$querySections(AABB box, AbortableIterationConsumer<EntitySection<T>> consumer,
                                     Operation<Void> original) {
        int minX = SectionPos.blockToSectionCoord(box.minX - 2.0);
        int minY = SectionPos.blockToSectionCoord(box.minY - 4.0);
        int minZ = SectionPos.blockToSectionCoord(box.minZ - 2.0);
        int maxX = SectionPos.blockToSectionCoord(box.maxX + 2.0);
        int maxY = SectionPos.blockToSectionCoord(box.maxY);
        int maxZ = SectionPos.blockToSectionCoord(box.maxZ + 2.0);
        // Preserve the original behavior outside the packed domain, including its overflowing upper endpoint.
        if (minX < -2_097_152 || maxX >= 2_097_151 || minZ < -2_097_152 || maxZ > 2_097_151) {
            original.call(box, consumer);
            return;
        }
        SectionQueryIndex<T> index = this.async$queryIndex();
        int cursor = index.lowerBound(SectionPos.asLong(minX, 0, 0));
        while (cursor < index.size()) {
            int x = SectionPos.x(index.positionAt(cursor));
            if (x > maxX) return;
            if (minZ < 0 && maxZ >= 0) {
                // Packed Z is unsigned within each X slice: nonnegative Z precedes negative Z.
                if (async$queryZRange(index, x, 0, maxZ, minY, maxY, consumer)
                        || async$queryZRange(index, x, minZ, -1, minY, maxY, consumer)) return;
            } else if (async$queryZRange(index, x, minZ, maxZ, minY, maxY, consumer)) {
                return;
            }
            // Vanilla snapshots one X slice at a time. A callback may create a later slice.
            if (this.async$queryIndex != index) index = this.async$queryIndex();
            cursor = index.lowerBound(SectionPos.asLong(x + 1, 0, 0));
        }
    }

    @Unique
    private boolean async$queryZRange(SectionQueryIndex<T> index, int x, int minZ, int maxZ, int minY, int maxY,
                                      AbortableIterationConsumer<EntitySection<T>> consumer) {
        if (minZ > maxZ) return false;
        long first = SectionPos.asLong(x, 0, minZ);
        long end = SectionPos.asLong(x, -1, maxZ) + 1L;
        for (int cursor = index.lowerBound(first); cursor < index.size(); cursor++) {
            long key = index.positionAt(cursor);
            if (key >= end) break;
            int y = SectionPos.y(key);
            if (y < minY || y > maxY) continue;
            // A reentrant callback may remove or replace a section after the query started.
            EntitySection<T> section = this.async$queryIndex == index ? index.sectionAt(cursor) : this.sections.get(key);
            if (section != null && !section.isEmpty() && section.getStatus().isAccessible()
                    && consumer.accept(section).shouldAbort()) return true;
        }
        return false;
    }
}
