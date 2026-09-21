package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** Keeps player state and the actual interaction participant under the synchronous call protocol. */
@Mixin(Player.class)
public class PlayerInteractionMixin {
    @WrapMethod(method = {"touch", "attack"})
    private void tickweave$interaction(Entity target, Operation<Void> original) {
        EntityTasks.call((Player) (Object) this, target, () -> original.call(target));
    }

    @WrapMethod(method = "interactOn")
    private InteractionResult tickweave$interact(Entity target, InteractionHand hand, Operation<InteractionResult> original) {
        return EntityTasks.call((Player) (Object) this, target, () -> original.call(target, hand));
    }

    @WrapMethod(method = "hurt")
    private boolean tickweave$hurt(DamageSource source, float amount, Operation<Boolean> original) {
        return EntityTasks.damage((Player) (Object) this, source, () -> original.call(source, amount));
    }
}
