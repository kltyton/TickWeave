package com.axalotl.async.forge.mixin.server;

import com.axalotl.async.common.parallelised.fastutil.Int2ObjectConcurrentHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.entity.PartEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Forge multipart collision queries safe during entity tracking changes. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPartsMixin {
    @Shadow @Final @Mutable
    private Int2ObjectMap<PartEntity<?>> dragonParts;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void tickweave$parallelParts(CallbackInfo ci) {
        var replacement = new Int2ObjectConcurrentHashMap<PartEntity<?>>();
        replacement.defaultReturnValue(dragonParts.defaultReturnValue());
        replacement.putAll(dragonParts);
        dragonParts = replacement;
    }
}
