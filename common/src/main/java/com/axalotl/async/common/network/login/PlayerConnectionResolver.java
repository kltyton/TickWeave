package com.axalotl.async.common.network.login;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Resolves a native field-chain read against the real transport while its play listener is absent. */
public final class PlayerConnectionResolver {
    private PlayerConnectionResolver() {}

    public static Object connectionFor(Object playerObject) {
        ServerPlayer player = (ServerPlayer) playerObject;
        ServerGamePacketListenerImpl listener = player.connection;
        if (listener != null) return listener.connection;
        LoginSession session = ((LoginSessionAccess) player).tickweave$loginSession();
        if (session != null) return session.connection();
        throw new NullPointerException("Player has no play listener or active login transport");
    }
}
