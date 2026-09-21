/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value={NearestLivingEntitySensor.class}, priority=1500)
public class NearestLivingEntitySensorMixin<T extends LivingEntity> {
    @Redirect(method={"doTick"}, at=@At(value="INVOKE", target="Ljava/util/Comparator;comparingDouble(Ljava/util/function/ToDoubleFunction;)Ljava/util/Comparator;"))
    private <E extends LivingEntity> Comparator<E> async$safeComparator(ToDoubleFunction<? super E> keyExtractor, ServerLevel world, T entity) {
        if (AsyncConfig.disabled.getValue().booleanValue()) {
            return Comparator.comparingDouble(keyExtractor);
        }
        return SensorUtils.distanceComparator(entity);
    }
}
