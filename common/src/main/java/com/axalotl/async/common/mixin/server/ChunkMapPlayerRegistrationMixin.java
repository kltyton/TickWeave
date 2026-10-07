package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.entity.task.EntityTasks;
import java.util.function.Supplier;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Commits player section and distance registration together without moving tracking loops. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapPlayerRegistrationMixin {
    @Shadow @Final private PlayerMap playerMap;

    @Unique
    private Object[] tickweave$moveRegistration(ServerPlayer player, Supplier<Object[]> operation) {
        return EntityTasks.onMain(() -> EntityTasks.call(player, () -> {
            // A queued move must not register a player whose tracking already ended.
            return playerMap.getPlayers(0L).contains(player) ? operation.get() : null;
        }));
    }

    @Unique
    private Object[] tickweave$statusRegistration(ServerPlayer player, Supplier<Object[]> operation) {
        return EntityTasks.onMain(() -> EntityTasks.call(player, operation));
    }
}
