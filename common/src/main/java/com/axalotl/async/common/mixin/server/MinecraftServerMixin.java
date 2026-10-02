package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.ParallelProcessor;
import net.minecraft.commands.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={MinecraftServer.class}, priority=0x7FFFFFFF)
public abstract class MinecraftServerMixin
extends ReentrantBlockableEventLoop<TickTask>
implements CommandSource,
AutoCloseable {
    public MinecraftServerMixin(String string) {
        super(string);
    }

    @Redirect(method={"reloadResources"}, at=@At(value="INVOKE", target="Lnet/minecraft/server/MinecraftServer;isSameThread()Z"))
    private boolean onServerExecutionThreadPatch(MinecraftServer minecraftServer) {
        return minecraftServer.isSameThread() || ParallelProcessor.isServerExecutionThread();
    }

    @Inject(method="tickServer", at=@At("RETURN"))
    private void async$finishStatistics(CallbackInfo ci) {
        com.axalotl.async.common.utils.TickStats.onServerTick();
    }

    @Inject(method={"stopServer"}, at={@At(value="HEAD")})
    private void beforeStopServer(CallbackInfo ci) {
        ParallelProcessor.stop();
    }
}

