package com.axalotl.async.common.mixin.server;

import com.axalotl.async.common.entity.task.EntityTasks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** Gives recursive passenger ticks the same execution owner and prevents double ticks after mount changes. */
@Mixin(ServerLevel.class)
public class EntityTickOwnershipMixin {
    @WrapMethod(method = "tickNonPassenger")
    private void tickweave$root(Entity entity, Operation<Void> original) {
        ServerLevel world = (ServerLevel) (Object) this;
        EntityTasks.execute(entity, () -> {
            if (entity.level() == world && !entity.isRemoved() && EntityTasks.claimTick(world, entity)) original.call(entity);
        });
    }

    @WrapMethod(method = "tickPassenger")
    private void tickweave$passenger(Entity vehicle, Entity passenger, Operation<Void> original) {
        ServerLevel world = (ServerLevel) (Object) this;
        EntityTasks.execute(passenger, () -> {
            if (passenger.isRemoved() || passenger.getVehicle() != vehicle || EntityTasks.claimTick(world, passenger)) {
                original.call(vehicle, passenger);
            }
        });
    }
}
