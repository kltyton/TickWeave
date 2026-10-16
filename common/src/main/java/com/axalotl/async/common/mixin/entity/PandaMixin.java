package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.entity.interaction.ItemPickup;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value={Panda.class})
public class PandaMixin {
    @Unique
    private static final Object async$lock = new Object();

    /** The item and collector must be owned before entering the pickup monitor. */
    @WrapMethod(method={"pickUpItem"})
    private void pickUpItem(ItemEntity itemEntity, Operation<Void> original) {
        ItemPickup.run((Panda) (Object) this, itemEntity, async$lock, () -> original.call(itemEntity));
    }
}

