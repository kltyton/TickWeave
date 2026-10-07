package com.axalotl.async.forge.mixin.entity;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Mixin;

/** Keeps pickup listeners on the world thread with both pickup participants owned. */
@Mixin(ForgeEventFactory.class)
public class ItemPickupEventMixin {
    @WrapMethod(method = "onItemPickup", remap = false)
    private static int tickweave$pickupEvent(ItemEntity item, Player player, Operation<Integer> original) {
        return EntityTasks.onMain(() -> EntityTasks.call(item, player, () -> original.call(item, player)));
    }

    @WrapMethod(method = "firePlayerItemPickupEvent", remap = false)
    private static void tickweave$pickedUpEvent(Player player, ItemEntity item, ItemStack pickedUp,
                                               Operation<Void> original) {
        EntityTasks.onMain(() -> EntityTasks.call(item, player, () -> original.call(player, item, pickedUp)));
    }
}
