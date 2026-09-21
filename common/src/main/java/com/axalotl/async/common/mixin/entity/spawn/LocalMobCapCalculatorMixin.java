/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  it.unimi.dsi.fastutil.longs.Long2ObjectMap
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.level.ChunkPos
 *  net.minecraft.world.level.LocalMobCapCalculator
 *  net.minecraft.world.level.LocalMobCapCalculator$MobCounts
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Shadow
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
 */
package com.axalotl.async.common.mixin.entity.spawn;

import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.entity.task.EntityTasks;
import com.axalotl.async.common.parallelised.fastutil.Long2ObjectConcurrentHashMap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

@Mixin(value={LocalMobCapCalculator.class})
public class LocalMobCapCalculatorMixin {
    @Shadow @Final @Mutable
    private Map<ServerPlayer, LocalMobCapCalculator.MobCounts> playerMobCounts;
    @Shadow @Final @Mutable
    private Long2ObjectMap<List<ServerPlayer>> playersNearChunk;

    @Inject(method="<init>", at=@At("RETURN"))
    private void async$init(CallbackInfo ci) {
        playerMobCounts = new java.util.concurrent.ConcurrentHashMap<>(playerMobCounts);
        Long2ObjectMap<List<ServerPlayer>> concurrent = new Long2ObjectConcurrentHashMap<>();
        concurrent.putAll(playersNearChunk);
        playersNearChunk = concurrent;
    }

    @WrapMethod(method = "getPlayersNear")
    private List<ServerPlayer> async$getPlayersNear(ChunkPos pos, Operation<List<ServerPlayer>> original) {
        if (!ParallelProcessor.isServerExecutionThread()) return original.call(pos);
        List<ServerPlayer> cached = this.playersNearChunk.get(pos.toLong());
        if (cached != null) return cached;
        // A cache miss reads the distance manager and player map, both owned by the server thread.
        return EntityTasks.onMain(() -> original.call(pos));
    }
}
