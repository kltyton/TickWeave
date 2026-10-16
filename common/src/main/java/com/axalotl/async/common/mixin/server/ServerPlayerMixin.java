package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.axalotl.async.common.network.login.LoginSession;
import com.axalotl.async.common.network.login.LoginSessionAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value={ServerPlayer.class})
public abstract class ServerPlayerMixin
extends Player
implements LoginSessionAccess {
    public ServerPlayerMixin(Level world, BlockPos pos, float yaw, GameProfile gameProfile) {
        super(world, pos, yaw, gameProfile);
    }

    @Unique private volatile LoginSession tickweave$loginSession;

    @Override public LoginSession tickweave$loginSession() { return tickweave$loginSession; }

    @Override public void tickweave$loginSession(LoginSession session) { this.tickweave$loginSession = session; }

    @WrapMethod(method={"die"})
    private void onDead(DamageSource damageSource, Operation<Void> original) {
        EntityTasks.damage(this, damageSource, () -> original.call(damageSource));
    }

    @Unique
    private void tickweave$chunkDelivery(Runnable operation) {
        EntityTasks.onMain(() -> EntityTasks.onThread(this, () -> {
            operation.run();
            return null;
        }));
    }

    @WrapMethod(method = "untrackChunk")
    private void tickweave$untrackChunk(ChunkPos position, Operation<Void> original) {
        tickweave$chunkDelivery(() -> original.call(position));
    }
}

