package com.axalotl.async.common.mixin.accessor;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Prepares the native player cache on the owner thread before parallel spawning. */
@Mixin(LocalMobCapCalculator.class)
public interface LocalMobCapCalculatorAccessor {
    @Invoker("getPlayersNear")
    List<ServerPlayer> tickweave$getPlayersNear(ChunkPos pos);
}
