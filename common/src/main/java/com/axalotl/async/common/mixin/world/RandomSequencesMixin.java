package com.axalotl.async.common.mixin.world;

import com.axalotl.async.common.parallelised.ConcurrentCollections;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.RandomSequence;
import net.minecraft.world.RandomSequences;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value={RandomSequences.class})
public class RandomSequencesMixin {
    @Shadow
    private final Map<ResourceLocation, RandomSequence> sequences = ConcurrentCollections.newHashMap();
}

