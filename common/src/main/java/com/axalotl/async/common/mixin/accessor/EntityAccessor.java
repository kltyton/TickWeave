/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.world.entity.Entity
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.gen.Accessor
 */
package com.axalotl.async.common.mixin.accessor;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value={Entity.class})
public interface EntityAccessor {
    @Accessor(value="isInsidePortal")
    public boolean isInsidePortal();

    @Accessor(value="boardingCooldown")
    public void setBoardingCooldown(int var1);

    @Invoker("canBeCollidedWith")
    boolean tickweave$invokeCanBeCollidedWith();

    @Invoker("canCollideWith")
    boolean tickweave$invokeCanCollideWith(Entity other);

    @Invoker("isPassengerOfSameVehicle")
    boolean tickweave$invokeSameVehicle(Entity other);

    @Invoker("isSpectator")
    boolean tickweave$invokeIsSpectator();
}

