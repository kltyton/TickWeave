/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
 *  com.llamalad7.mixinextras.injector.wrapoperation.Operation
 *  net.minecraft.core.BlockPos
 *  net.minecraft.tags.BlockTags
 *  net.minecraft.util.Mth
 *  net.minecraft.world.damagesource.DamageSource
 *  net.minecraft.world.effect.MobEffect
 *  net.minecraft.world.effect.MobEffectInstance
 *  net.minecraft.world.entity.Entity
 *  net.minecraft.world.entity.EntityType
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.level.Level
 *  net.minecraft.world.level.block.state.BlockState
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Unique
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
 */
package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={LivingEntity.class}, priority=1001)
public abstract class LivingEntityMixin
extends Entity {
    @Shadow @Final @Mutable
    private Map<MobEffect, MobEffectInstance> activeEffects;

    public LivingEntityMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @Inject(method="<init>", at=@At("TAIL"))
    private void async$concurrentEffects(CallbackInfo ci) {
        this.activeEffects = new ConcurrentHashMap<>(this.activeEffects);
    }

    @WrapMethod(method={"die"})
    private void die(DamageSource damageSource, Operation<Void> original) {
        EntityTasks.damage(this, damageSource, () -> original.call(damageSource));
    }

    @WrapMethod(method={"dropFromLootTable(Lnet/minecraft/world/damagesource/DamageSource;Z)V"})
    private void dropFromLootTable(DamageSource damageSource, boolean playerKill, Operation<Void> original) {
        EntityTasks.damage(this, damageSource, () -> original.call(damageSource, playerKill));
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @WrapMethod(method={"knockback"})
    private void knockback(double strength, double x, double z, Operation<Void> original) {
        EntityTasks.execute(this, () -> original.call(strength, x, z));
    }

    @WrapMethod(method={"tickEffects", "updateInvisibilityStatus"})
    private void tickStatusEffects(Operation<Void> original) {
        EntityTasks.execute(this, () -> original.call());
    }

    @WrapOperation(method="tickEffects", at=@At(value="INVOKE", target="Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object async$readTickingEffect(Map<?, ?> effects, Object key, Operation<Object> original) {
        Object effect = original.call(effects, key);
        if (effect == null) {
            // The native loop catches invalidation and still updates dirty effect metadata afterwards.
            throw new ConcurrentModificationException("Effect removed during tick iteration");
        }
        return effect;
    }

    @ModifyExpressionValue(method="updateInvisibilityStatus", at=@At(value="INVOKE", target="Ljava/util/Map;values()Ljava/util/Collection;"))
    private Collection<MobEffectInstance> async$snapshotEffectColors(Collection<MobEffectInstance> effects) {
        // The concurrent values view also covers writes through the public effects map.
        return new ArrayList<>(effects);
    }

    @WrapMethod(method="forceAddEffect")
    private void async$forceAddEffect(MobEffectInstance effect, Entity source, Operation<Void> original) {
        EntityTasks.call(this, source, () -> original.call(effect, source));
    }

    @WrapMethod(method="removeEffectNoUpdate")
    private MobEffectInstance async$removeEffectNoUpdate(MobEffect effect, Operation<MobEffectInstance> original) {
        return EntityTasks.call(this, () -> effect == null ? null : original.call(effect));
    }

    @Inject(method="getEffect", at=@At("HEAD"), cancellable=true)
    private void async$getEffect(MobEffect effect, CallbackInfoReturnable<MobEffectInstance> cir) {
        if (effect == null) cir.setReturnValue(null);
    }

    @WrapMethod(method="onEffectAdded")
    private void async$onEffectAdded(MobEffectInstance effect, Entity source, Operation<Void> original) {
        EntityTasks.call(this, source, () -> original.call(effect, source));
    }

    @WrapMethod(method="onEffectUpdated")
    private void async$onEffectUpdated(MobEffectInstance effect, boolean forced, Entity source, Operation<Void> original) {
        EntityTasks.call(this, source, () -> original.call(effect, forced, source));
    }

    @WrapMethod(method="onEffectRemoved")
    private void async$onEffectRemoved(MobEffectInstance effect, Operation<Void> original) {
        EntityTasks.execute(this, () -> original.call(effect));
    }

    // Forge adds this method; Fabric has no matching target.
    @WrapMethod(method="curePotionEffects", remap=false, require=0)
    private boolean async$curePotionEffects(ItemStack curativeItem, Operation<Boolean> original) {
        return EntityTasks.call(this, () -> original.call(curativeItem));
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @WrapMethod(method={"addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z"})
    private boolean addEffect(MobEffectInstance effect, Entity source, Operation<Boolean> original) {
        return EntityTasks.call(this, source, () -> effect != null ? original.call(effect, source) : false);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @WrapMethod(method={"removeEffect"})
    private boolean removeEffect(MobEffect effect, Operation<Boolean> original) {
        return EntityTasks.call(this, () -> effect != null ? (Boolean)original.call(new Object[]{effect}) : false);
    }

    @Inject(method="hasEffect", at=@At("HEAD"), cancellable=true)
    private void async$hasEffect(MobEffect effect, CallbackInfoReturnable<Boolean> cir) {
        if (effect == null) cir.setReturnValue(false);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @WrapMethod(method={"removeAllEffects"})
    private boolean removeAllEffects(Operation<Boolean> original) {
        return EntityTasks.call(this, () -> (Boolean)original.call(new Object[0]));
    }

    @WrapMethod(method = "hurt")
    private boolean tickweave$hurt(DamageSource source, float amount, Operation<Boolean> original) {
        return EntityTasks.damage(this, source, () -> original.call(source, amount));
    }

    @WrapMethod(method = "heal")
    private void tickweave$heal(float amount, Operation<Void> original) {
        EntityTasks.execute(this, () -> original.call(amount));
    }

    @WrapMethod(method = "setHealth")
    private void tickweave$health(float amount, Operation<Void> original) {
        EntityTasks.execute(this, () -> original.call(amount));
    }

    @Inject(method={"causeFallDamage"}, at={@At(value="HEAD")}, cancellable=true)
    private void causeFallDamage(float fallDistance, float multiplier, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        BlockPos pos = new BlockPos(Mth.floor((double)this.getX()), Mth.floor((double)this.getY()), Mth.floor((double)this.getZ()));
        BlockState currentBlock = this.level().getBlockState(pos);
        if (currentBlock.is(BlockTags.CLIMBABLE)) {
            cir.setReturnValue(false);
        }
    }
}
