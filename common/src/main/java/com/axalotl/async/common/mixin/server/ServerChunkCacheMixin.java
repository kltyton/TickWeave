package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.AsyncRandomTicks;
import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.entity.task.EntityTasks;
import com.axalotl.async.common.entity.task.CooperativeTask;
import com.axalotl.async.common.chunk.ChunkRequest;
import com.axalotl.async.common.config.AsyncConfig;
import com.axalotl.async.common.mixin.accessor.LocalMobCapCalculatorAccessor;
import com.axalotl.async.common.mixin.accessor.SpawnStateAccessor;
import com.axalotl.async.common.utils.LastChunkCache;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Either;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps parallel chunk work inside its tick phase and avoids scheduling chunk loads on workers. */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin extends ChunkSource {
    @Shadow @Final public ServerLevel level;
    @Shadow @Final Thread mainThread;
    @Shadow @Final public ChunkMap chunkMap;
    @Shadow @Final private ServerChunkCache.MainThreadExecutor mainThreadProcessor;
    @Unique private final ThreadLocal<LastChunkCache> async$lastChunk = ThreadLocal.withInitial(LastChunkCache::new);
    @Unique private final List<LevelChunk> async$chunksToTick = new ArrayList<>();
    @Unique private final List<Runnable> async$spawnTasks = new ArrayList<>();
    @Unique private final ConcurrentMap<ChunkRequest, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>>
            tickweave$pendingChunks = new ConcurrentHashMap<>();

    @Shadow @Nullable protected abstract ChunkHolder getVisibleChunkIfPresent(long position);
    @Shadow protected abstract CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>
            getChunkFutureMainThread(int x, int z, ChunkStatus status, boolean create);

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void async$getChunk(int x, int z, ChunkStatus status, boolean create, CallbackInfoReturnable<ChunkAccess> cir) {
        if (Thread.currentThread() == mainThread || !ParallelProcessor.isServerExecutionThread()) return;
        int tick = level.getServer().getTickCount();
        long position = ChunkPos.asLong(x, z);
        LastChunkCache cache = async$lastChunk.get();
        ChunkAccess cached = cache.find(this, position, status, tick);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }
        ChunkAccess chunk = async$readyChunk(x, z, status);
        if (chunk == null) {
            ChunkRequest request = new ChunkRequest(position, status, create);
            CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> pending =
                    tickweave$pendingChunks.compute(request, (ignored, current) ->
                            current != null && !current.isDone() ? current : CooperativeTask
                            .composeAsync(() -> getChunkFutureMainThread(x, z, status, create), mainThreadProcessor));
            pending.whenComplete((result, failure) -> tickweave$pendingChunks.remove(request, pending));
            Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result = EntityTasks.await(pending);
            chunk = result.left().orElse(null);
            if (chunk instanceof ImposterProtoChunk imposter) chunk = imposter.getWrapped();
            if (chunk == null && create) throw new IllegalStateException("Chunk unavailable at " + position + ": " + result);
        }
        if (chunk != null) cache.store(this, position, status, chunk, tick);
        cir.setReturnValue(chunk);
    }

    @Unique @Nullable
    private ChunkAccess async$readyChunk(int x, int z, ChunkStatus status) {
        ChunkHolder holder = getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        if (holder == null) return null;
        Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> result = holder.getFutureIfPresent(status).getNow(null);
        if (result == null) return null;
        ChunkAccess chunk = result.left().orElse(null);
        return chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : chunk;
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void async$getChunkNow(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        if (Thread.currentThread() == mainThread || !ParallelProcessor.isServerExecutionThread()) return;
        ChunkAccess chunk = async$readyChunk(x, z, ChunkStatus.FULL);
        cir.setReturnValue(chunk instanceof LevelChunk full ? full : null);
    }

    @WrapMethod(method = "tickChunks")
    private void async$chunkPhase(Operation<Void> original) {
        try {
            original.call();
        } finally {
            async$chunksToTick.clear();
            async$spawnTasks.clear();
        }
    }

    @Redirect(method = "tickChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V"))
    private void async$collectChunk(ServerLevel world, LevelChunk chunk, int speed) {
        if (!AsyncConfig.disabled.getValue() && AsyncConfig.enableAsyncRandomTicks.getValue()) {
            async$chunksToTick.add(chunk);
        } else {
            world.tickChunk(chunk, speed);
        }
    }

    @Redirect(method = "tickChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;ZZZ)V"))
    private void async$collectSpawn(ServerLevel world, LevelChunk chunk, NaturalSpawner.SpawnState state,
                                   boolean animals, boolean monsters, boolean rare) {
        if (!AsyncConfig.disabled.getValue() && AsyncConfig.enableAsyncSpawn.getValue()) {
            var calculator = ((SpawnStateAccessor) state).tickweave$getLocalMobCapCalculator();
            ((LocalMobCapCalculatorAccessor) calculator).tickweave$getPlayersNear(chunk.getPos());
            async$spawnTasks.add(() -> NaturalSpawner.spawnForChunk(world, chunk, state, animals, monsters, rare));
        } else {
            NaturalSpawner.spawnForChunk(world, chunk, state, animals, monsters, rare);
        }
    }

    @Inject(method = "tickChunks", at = @At(value = "CONSTANT", args = "stringValue=customSpawners"))
    private void async$finishChunkWork(CallbackInfo ci) {
        ParallelProcessor.forEachParallel(async$spawnTasks, Runnable::run, ParallelProcessor.SPAWN_COST);
        async$spawnTasks.clear();
        if (!async$chunksToTick.isEmpty()) {
            AsyncRandomTicks.tickChunks(level, async$chunksToTick, level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING));
            async$chunksToTick.clear();
        }
    }
}
