package com.axalotl.async.forge.mixin.world;

import com.axalotl.async.common.mixin.accessor.LightEngineAccessor;
import com.axalotl.async.forge.world.LightingBlockEntities;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.BlockLightEngine;
import org.spongepowered.asm.mixin.Mixin;

/** Keeps dynamic Forge light callbacks inside the lighting chunk-access boundary. */
@Mixin(BlockLightEngine.class)
public class BlockLightEngineMixin {
    @WrapMethod(method = "getEmission")
    private int tickweave$emission(long position, BlockState state, Operation<Integer> original) {
        try (var ignored = LightingBlockEntities.open(((LightEngineAccessor) this).tickweave$chunkSource())) {
            return original.call(position, state);
        }
    }

    @WrapMethod(method = "propagateLightSources")
    private void tickweave$lightSources(ChunkPos position, Operation<Void> original) {
        try (var ignored = LightingBlockEntities.open(((LightEngineAccessor) this).tickweave$chunkSource())) {
            original.call(position);
        }
    }
}
