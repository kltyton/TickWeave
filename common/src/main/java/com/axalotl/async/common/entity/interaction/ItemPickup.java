package com.axalotl.async.common.entity.interaction;

import com.axalotl.async.common.entity.task.EntityTasks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/** Acquires both participants before a pickup callback enters its shared monitor. */
public final class ItemPickup {
    private ItemPickup() {}

    public static void run(Entity receiver, ItemEntity item, Object monitor, Runnable action) {
        EntityTasks.call(receiver, item, () -> {
            synchronized (monitor) {
                if (!item.isRemoved()) action.run();
            }
            return null;
        });
    }
}
