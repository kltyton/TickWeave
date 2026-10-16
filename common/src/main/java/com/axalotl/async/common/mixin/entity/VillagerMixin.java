package com.axalotl.async.common.mixin.entity;

import com.axalotl.async.common.entity.interaction.ItemPickup;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value={Villager.class})
public class VillagerMixin {
    @Unique
    private static final Object async$lock = new Object();

    /** The item and collector must be owned before entering the pickup monitor. */
    @WrapMethod(method={"pickUpItem"})
    private void pickUpItem(ItemEntity itemEntity, Operation<Void> original) {
        ItemPickup.run((Villager) (Object) this, itemEntity, async$lock, () -> original.call(itemEntity));
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @WrapMethod(method={"spawnGolemIfNeeded"})
    private void spawnGolemIfNeeded(ServerLevel world, long time, int requiredCount, Operation<Void> original) {
        Object object = async$lock;
        synchronized (object) {
            original.call(new Object[]{world, time, requiredCount});
        }
    }
}

