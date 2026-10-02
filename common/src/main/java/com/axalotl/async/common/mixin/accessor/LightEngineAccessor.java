package com.axalotl.async.common.mixin.accessor;

import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightEngine.class)
public interface LightEngineAccessor {
    @Accessor("chunkSource")
    LightChunkGetter tickweave$chunkSource();
}
