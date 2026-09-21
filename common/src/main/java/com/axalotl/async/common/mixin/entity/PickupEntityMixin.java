package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** Runs pickup events and inventory or XP changes with both participants owned. */
@Mixin({ItemEntity.class, ExperienceOrb.class})
public class PickupEntityMixin {
    @WrapMethod(method = "playerTouch")
    private void tickweave$pickup(Player player, Operation<Void> original) {
        EntityTasks.call((Entity) (Object) this, player, () -> original.call(player));
    }

    @WrapMethod(method = "hurt")
    private boolean tickweave$hurt(DamageSource source, float amount, Operation<Boolean> original) {
        return EntityTasks.damage((Entity) (Object) this, source, () -> original.call(source, amount));
    }
}
