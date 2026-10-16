package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.entity.task.PlayerNetworkTasks;
import com.axalotl.async.common.entity.task.ConnectionTaskAccess;
import com.axalotl.async.common.entity.task.CooperativeTask;
import com.axalotl.async.common.network.login.LoginSession;
import com.axalotl.async.common.network.login.LoginSessionAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Guards packet ownership and preserves native login packet ordering on each connection. */
@Mixin(Connection.class)
public class PlayerPacketDispatchMixin implements ConnectionTaskAccess, LoginSessionAccess {
    @Shadow private Channel channel;
    @Unique private final CooperativeTask tickweave$connectionTask = new CooperativeTask();
    @Unique private volatile LoginSession tickweave$loginSession;

    @Override public CooperativeTask tickweave$connectionTask() { return tickweave$connectionTask; }
    @Override public LoginSession tickweave$loginSession() { return tickweave$loginSession; }

    @Override public void tickweave$loginSession(LoginSession session) {
        try {
            if (session == null && tickweave$loginSession != null) {
                tickweave$loginSession.close(channel.eventLoop(), packet ->
                        channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE));
            }
        } finally {
            tickweave$loginSession = session;
        }
    }

    @WrapMethod(method = "genericsFtw")
    private static void tickweave$receive(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        PlayerNetworkTasks.receive(listener, () -> original.call(packet, listener));
    }

    @WrapMethod(method = "sendPacket")
    private void tickweave$sendEmbedded(Packet<?> packet, PacketSendListener listener, Operation<Void> original) {
        Channel current = channel;
        if (current instanceof EmbeddedChannel) {
            synchronized (current) {
                original.call(packet, listener);
            }
        } else {
            original.call(packet, listener);
        }
    }

    /** Resumes each captured send after the native first play packet has entered the send chain. */
    @WrapMethod(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V")
    private void tickweave$queueLoginPayload(Packet<?> packet, PacketSendListener listener, Operation<Void> original) {
        LoginSession session = tickweave$loginSession;
        if (session != null && session.enqueue(packet, listener, () -> original.call(packet, listener))) return;
        original.call(packet, listener);
        if (session != null && packet instanceof ClientboundLoginPacket) session.release();
    }
}
