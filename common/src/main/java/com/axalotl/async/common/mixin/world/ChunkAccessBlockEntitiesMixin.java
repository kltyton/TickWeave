package com.axalotl.async.common.mixin.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps block-entity value snapshots valid while parallel queries add or remove entries. */
@Mixin(ChunkAccess.class)
public class ChunkAccessBlockEntitiesMixin {
    @Shadow @Final @Mutable protected Map<BlockPos, BlockEntity> blockEntities;
    @Shadow @Final @Mutable protected Map<BlockPos, CompoundTag> pendingBlockEntities;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void tickweave$blockEntityMaps(CallbackInfo ci) {
        blockEntities = new ConcurrentHashMap<>(blockEntities);
        pendingBlockEntities = new ConcurrentHashMap<>(pendingBlockEntities);
    }
}
