package com.axalotl.async.common.mixin.accessor;

import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value={LevelChunk.class})
public interface LevelChunkAccessor {
    @Accessor(value="loaded")
    public boolean isLoaded();
}

