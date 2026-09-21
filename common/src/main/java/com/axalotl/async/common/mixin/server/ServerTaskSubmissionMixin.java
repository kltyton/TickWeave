package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.entity.task.EntityTasks;
import com.axalotl.async.common.entity.task.PlayerNetworkTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.CompletionException;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;

/** Makes blocking server submissions reachable while the owner waits for entity work. */
@Mixin(BlockableEventLoop.class)
public class ServerTaskSubmissionMixin {
    @WrapMethod(method = "execute")
    private void tickweave$capturePlayer(Runnable action, Operation<Void> original) {
        original.call((Object) this == ParallelProcessor.getServer() ? PlayerNetworkTasks.capture(action) : action);
    }

    @WrapMethod(method = "executeBlocking")
    private void tickweave$blockingServerTask(Runnable action, Operation<Void> original) {
        if ((Object) this != ParallelProcessor.getServer()
                || ((BlockableEventLoop<?>) (Object) this).isSameThread()) {
            original.call(action);
            return;
        }
        Runnable captured = PlayerNetworkTasks.capture(action);
        Throwable failure = EntityTasks.onMain(() -> {
            try {
                original.call(captured);
                return null;
            } catch (Throwable thrown) {
                return thrown;
            }
        });
        // The native off-thread path uses CompletableFuture.join, including its exception contract.
        if (failure instanceof CompletionException completed) throw completed;
        if (failure != null) throw new CompletionException(failure);
    }
}
