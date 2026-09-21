/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
 *  com.llamalad7.mixinextras.injector.wrapoperation.Operation
 *  net.minecraft.world.entity.EntityType
 *  net.minecraft.world.entity.EquipmentSlot
 *  net.minecraft.world.entity.Mob
 *  net.minecraft.world.entity.item.ItemEntity
 *  net.minecraft.world.item.ItemStack
 *  org.jetbrains.annotations.Nullable
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Unique
 */
package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value={Mob.class})
public class MobMixin {

    /** Target changes fire synchronous events that can mutate the receiving mob. */
    @WrapMethod(method = "setTarget")
    private void tickweave$target(@Nullable LivingEntity target, Operation<Void> original) {
        EntityTasks.call((Mob) (Object) this, target, () -> original.call(target));
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @WrapMethod(method={"equipItemIfPossible"})
    private ItemStack tryEquip(ItemStack stack, Operation<ItemStack> original) {
        return EntityTasks.call((Mob) (Object) this, () -> original.call(stack));
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @WrapMethod(method={"pickUpItem"})
    private void pickUpItem(ItemEntity entity, Operation<Void> original) {
        EntityTasks.call((Mob) (Object) this, entity, () ->
                EntityTasks.onMain((Mob) (Object) this, () -> original.call(entity)));
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @WrapMethod(method={"setItemSlotAndDropWhenKilled"})
    private void equipLootStack(EquipmentSlot slot, ItemStack stack, Operation<Void> original) {
        EntityTasks.execute((Mob) (Object) this, () -> original.call(slot, stack));
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @WrapMethod(method={"convertTo(Lnet/minecraft/world/entity/EntityType;Z)Lnet/minecraft/world/entity/Mob;"})
    @Nullable
    private <T extends Mob> T convertTo(EntityType<T> entityType, boolean mysteryBool, Operation<T> original) {
        return EntityTasks.onMain((Mob) (Object) this, () -> ((Mob) (Object) this).isRemoved() ? null : original.call(entityType, mysteryBool));
    }
}

