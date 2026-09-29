package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.entity.task.PlayerNetworkTasks;
import com.axalotl.async.common.entity.task.ConnectionTaskAccess;
import com.axalotl.async.common.entity.task.CooperativeTask;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Guards incoming play packets and captures ownership for native and loader work queues. */
@Mixin(Connection.class)
public class PlayerPacketDispatchMixin implements ConnectionTaskAccess {
    @Shadow private Channel channel;
    @Unique private final CooperativeTask tickweave$connectionTask = new CooperativeTask();

    @Override public CooperativeTask tickweave$connectionTask() { return tickweave$connectionTask; }

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
}
