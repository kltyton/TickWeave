/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
 *  com.llamalad7.mixinextras.injector.wrapoperation.Operation
 *  com.mojang.datafixers.DataFixer
 *  it.unimi.dsi.fastutil.ints.Int2ObjectMap
 *  it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
 *  net.minecraft.server.level.ChunkHolder
 *  net.minecraft.server.level.ChunkHolder$PlayerProvider
 *  net.minecraft.server.level.ChunkMap
 *  net.minecraft.server.level.ChunkMap$TrackedEntity
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.level.chunk.storage.ChunkStorage
 *  org.spongepowered.asm.mixin.Final
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Mutable
 *  org.spongepowered.asm.mixin.Shadow
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.parallelised.fastutil.Int2ObjectConcurrentHashMap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={ChunkMap.class}, priority=1500)
public abstract class ChunkMapMixin
extends ChunkStorage
implements ChunkHolder.PlayerProvider {
    @Shadow
    @Final
    @Mutable
    private Int2ObjectMap<ChunkMap.TrackedEntity> entityMap;
    @Shadow
    private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;
    @Unique
    private volatile Map.Entry<Long2ObjectLinkedOpenHashMap<ChunkHolder>, ObjectCollection<ChunkHolder>> async$visibleChunks;

    public ChunkMapMixin(Path directory, DataFixer dataFixer, boolean dsync) {
        super(directory, dataFixer, dsync);
    }

    @Inject(method={"<init>"}, at={@At(value="TAIL")})
    private void replaceConVars(CallbackInfo ci) {
        this.entityMap = new Int2ObjectConcurrentHashMap<ChunkMap.TrackedEntity>();
    }

    @WrapOperation(method = {"getChunks", "processUnloads"}, at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;",
            remap = false))
    private ObjectCollection<ChunkHolder> async$visibleChunkValues(Long2ObjectLinkedOpenHashMap<ChunkHolder> map,
                                                                  Operation<ObjectCollection<ChunkHolder>> original) {
        var snapshot = this.async$visibleChunks;
        if (snapshot != null && snapshot.getKey() == map) return snapshot.getValue();
        // Visible maps are published as clones; only membership is cached, never holder readiness or dirty state.
        ObjectCollection<ChunkHolder> values = ObjectLists.unmodifiable(new ObjectArrayList<>(original.call(map)));
        this.async$visibleChunks = Map.entry(map, values);
        return values;
    }

    @WrapMethod(method={"addEntity"})
    private synchronized void addEntity(Entity entity, Operation<Void> original) {
        original.call(new Object[]{entity});
    }

    @WrapMethod(method={"removeEntity"})
    private synchronized void removeEntity(Entity entity, Operation<Void> original) {
        original.call(new Object[]{entity});
    }

    @Inject(method={"addEntity"}, at={@At(value="INVOKE", target="Lnet/minecraft/Util;pauseInIde(Ljava/lang/Throwable;)Ljava/lang/Throwable;")}, cancellable=true)
    private void skipThrowLoadEntity(Entity entity, CallbackInfo ci) {
        ci.cancel();
    }
}

