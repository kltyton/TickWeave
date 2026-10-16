package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.entity.interaction.ItemPickup;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Dolphin;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value={Dolphin.class})
public abstract class DolphinMixin
extends WaterAnimal {
    @Unique
    private static final Object async$lock = new Object();

    protected DolphinMixin(EntityType<? extends WaterAnimal> entityType, Level world) {
        super(entityType, world);
    }

    /** The item and collector must be owned before entering the pickup monitor. */
    @WrapMethod(method={"pickUpItem"})
    private void pickUpItem(ItemEntity item, Operation<Void> original) {
        ItemPickup.run(this, item, async$lock, () -> original.call(item));
    }
}

