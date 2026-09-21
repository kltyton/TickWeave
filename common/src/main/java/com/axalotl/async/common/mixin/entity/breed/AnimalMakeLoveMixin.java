/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.world.entity.ai.behavior.AnimalMakeLove
 *  net.minecraft.world.entity.ai.memory.MemoryModuleType
 *  net.minecraft.world.entity.animal.Animal
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Shadow
 *  org.spongepowered.asm.mixin.Unique
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
 */
package com.axalotl.async.common.mixin.entity.breed;

import com.llamalad7.mixinextras.sugar.Local;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.AnimalMakeLove;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = { AnimalMakeLove.class })
public abstract class AnimalMakeLoveMixin {
    @Shadow @Final private EntityType<? extends Animal> partnerType;

    @Inject(method = "hasBreedTargetOfRightType", at = @At("HEAD"), cancellable = true)
    private void async$checkPartnerType(Animal animal, CallbackInfoReturnable<Boolean> cir) {
        Optional<AgeableMob> memory = animal.getBrain().getMemoryInternal(MemoryModuleType.BREED_TARGET);
        cir.setReturnValue(memory != null && memory.isPresent() && memory.get().getType() == this.partnerType);
    }
    @Inject(method = {
            "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;J)V" }, at = {
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/BehaviorUtils;lockGazeAndWalkToEachOther(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;F)V") }, cancellable = true)
    private void tick(ServerLevel level, Animal owner, long gameTime, CallbackInfo ci, @Local(ordinal = 1) Animal target) {
        if (target == null) {
            ci.cancel();
        }
    }

    @Inject(method = {
            "canStillUse(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;J)Z" }, at = {
                    @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/Animal;isAlive()Z") }, cancellable = true)
    private void canStillUse(ServerLevel level, Animal entity, long gameTime, CallbackInfoReturnable<Boolean> cir,
                             @Local(ordinal = 1) Animal target) {
        if (target == null) {
            cir.setReturnValue(false);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Inject(method = { "getBreedTarget" }, at = { @At(value = "HEAD") }, cancellable = true)
    private void syncBreedTarget(Animal animal, CallbackInfoReturnable<Animal> cir) {
        cir.setReturnValue((Animal) animal.getBrain().getMemory(MemoryModuleType.BREED_TARGET).orElse(null));
    }
}
