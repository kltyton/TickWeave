package com.axalotl.async.common.network.login;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;

/** Keeps early play payloads behind the native first play packet on one login transport. */
public final class LoginSession {
    private enum Phase { PENDING, DRAINING, READY, CLOSED }
    private final Connection connection;
    private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
    private Phase phase = Phase.PENDING;

    public LoginSession(Connection connection) { this.connection = connection; }
    public Connection connection() { return connection; }

    /** Captures the remaining native send chain without repeating outer wrappers. */
    public synchronized boolean enqueue(Packet<?> packet, PacketSendListener listener, Runnable send) {
        if (!(packet instanceof ClientboundCustomPayloadPacket)
                || phase == Phase.READY || phase == Phase.CLOSED) return false;
        deferred.addLast(new Deferred(listener, send));
        return true;
    }

    public void release() {
        synchronized (this) {
            if (phase != Phase.PENDING) return;
            phase = Phase.DRAINING;
        }
        while (true) {
            Deferred next;
            synchronized (this) {
                if (phase == Phase.CLOSED) return;
                next = deferred.pollFirst();
                if (next == null) {
                    phase = Phase.READY;
                    return;
                }
            }
            next.send.run();
        }
    }

    /** Completes unsent callbacks with the native failed-write packet continuation. */
    public void close(Executor callbackExecutor, Consumer<Packet<?>> failedWrite) {
        ArrayDeque<Deferred> pending;
        synchronized (this) {
            phase = Phase.CLOSED;
            pending = new ArrayDeque<>(deferred);
            deferred.clear();
        }
        if (!pending.isEmpty()) callbackExecutor.execute(() -> completeFailures(pending, failedWrite));
    }

    private static void completeFailures(ArrayDeque<Deferred> pending, Consumer<Packet<?>> failedWrite) {
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        for (Deferred next : pending) {
            if (next.listener == null) continue;
            try {
                Packet<?> fallback = next.listener.onFailure();
                if (fallback != null) failedWrite.accept(fallback);
            } catch (RuntimeException failure) {
                if (runtimeFailure == null) runtimeFailure = failure;
                else if (runtimeFailure != failure) runtimeFailure.addSuppressed(failure);
            } catch (Error failure) {
                if (errorFailure == null) errorFailure = failure;
                else if (errorFailure != failure) errorFailure.addSuppressed(failure);
            }
        }
        if (errorFailure != null) {
            if (runtimeFailure != null) errorFailure.addSuppressed(runtimeFailure);
            throw errorFailure;
        }
        if (runtimeFailure != null) throw runtimeFailure;
    }

    private record Deferred(PacketSendListener listener, Runnable send) {}
}
