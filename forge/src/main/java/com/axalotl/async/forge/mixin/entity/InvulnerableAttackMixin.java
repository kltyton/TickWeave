package com.axalotl.async.forge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Avoids dispatching Forge attack events for damage the target cannot receive. */
@Mixin(LivingEntity.class)
public abstract class InvulnerableAttackMixin {
    @WrapOperation(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraftforge/common/ForgeHooks;onLivingAttack(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean tickweave$attackIfVulnerable(LivingEntity target, DamageSource source, float amount,
                                                   Operation<Boolean> original) {
        return !target.isInvulnerableTo(source) && original.call(target, source, amount);
    }
}
