package com.axalotl.async.common.mixin.entity;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.ExpirableValue;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Brain.class)
public class BrainMixin {
    @Shadow @Final @Mutable
    private Map<MemoryModuleType<?>, Optional<? extends ExpirableValue<?>>> memories;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void async$concurrentMemories(CallbackInfo ci) {
        // One store keeps normalization, clearing and reads in agreement, including access outside tick().
        this.memories = new ConcurrentHashMap<>(this.memories);
    }
}
