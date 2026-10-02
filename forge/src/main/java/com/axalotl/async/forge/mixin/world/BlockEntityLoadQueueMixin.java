package com.axalotl.async.forge.mixin.world;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Delivers cross-thread registrations to Forge's native onLoad phase. */
@Mixin(Level.class)
public abstract class BlockEntityLoadQueueMixin {
    @Shadow @Final private Thread thread;
    @Shadow(remap = false) public abstract void addFreshBlockEntities(Collection<BlockEntity> entities);
    @Unique private final ConcurrentLinkedQueue<List<BlockEntity>> tickweave$pendingLoads = new ConcurrentLinkedQueue<>();

    @Inject(method = "addFreshBlockEntities(Ljava/util/Collection;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void tickweave$queueLoads(Collection<BlockEntity> entities, CallbackInfo ci) {
        if (((Level) (Object) this).isClientSide || Thread.currentThread() == thread) return;
        tickweave$pendingLoads.add(List.copyOf(entities));
        ci.cancel();
    }

    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void tickweave$deliverLoads(CallbackInfo ci) {
        List<BlockEntity> entities;
        while ((entities = tickweave$pendingLoads.poll()) != null) addFreshBlockEntities(entities);
    }
}
