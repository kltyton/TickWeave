/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.world.entity.Mob
 *  net.minecraft.world.entity.ai.sensing.NearestItemSensor
 *  net.minecraft.world.entity.item.ItemEntity
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Redirect
 */
package com.axalotl.async.common.mixin.entity.sensor;

import com.axalotl.async.common.config.AsyncConfig;
import com.axalotl.async.common.parallelised.utils.SensorUtils;
import java.util.Comparator;
import java.util.function.ToDoubleFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.sensing.NearestItemSensor;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value={NearestItemSensor.class}, priority=1500)
public class NearestItemSensorMixin {
    @Redirect(method={"doTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;)V"}, at=@At(value="INVOKE", target="Ljava/util/Comparator;comparingDouble(Ljava/util/function/ToDoubleFunction;)Ljava/util/Comparator;"))
    private <T extends ItemEntity> Comparator<T> async$safeComparator(ToDoubleFunction<? super T> keyExtractor, ServerLevel world, Mob entity) {
        if (AsyncConfig.disabled.getValue().booleanValue()) {
            return Comparator.comparingDouble(keyExtractor);
        }
        return SensorUtils.distanceComparator(entity);
    }
}
