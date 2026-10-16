package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.network.login.LoginSession;
import com.axalotl.async.common.network.login.LoginSessionAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;

/** Binds the real transport before native login callbacks can access the player's play connection. */
@Mixin(PlayerList.class)
public class PlayerLoginMixin {
    @WrapMethod(method = "placeNewPlayer")
    private void tickweave$bindLogin(Connection connection, ServerPlayer player, Operation<Void> original) {
        LoginSession session = new LoginSession(connection);
        ((LoginSessionAccess) connection).tickweave$loginSession(session);
        ((LoginSessionAccess) player).tickweave$loginSession(session);
        Throwable originalFailure = null;
        try {
            original.call(connection, player);
        } catch (RuntimeException | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            try {
                ((LoginSessionAccess) connection).tickweave$loginSession(null);
            } catch (RuntimeException | Error cleanupFailure) {
                if (originalFailure == null) throw cleanupFailure;
                if (cleanupFailure != originalFailure) originalFailure.addSuppressed(cleanupFailure);
            } finally {
                ((LoginSessionAccess) player).tickweave$loginSession(null);
            }
        }
    }
}
