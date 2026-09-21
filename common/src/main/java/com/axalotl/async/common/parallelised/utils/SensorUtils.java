package com.axalotl.async.common.parallelised.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public class SensorUtils {

    /**
     * Wraps a sensor with custom tick logic for async-safe processing.
     */
    public static <T extends LivingEntity> Sensor<T> wrapSensor(Sensor<T> originalSensor, TickWrapper<T> wrapperLogic) {
        return new Sensor<T>() {
            @Override
            public Set<MemoryModuleType<?>> requires() {
                return originalSensor.requires();
            }

            @Override
            protected void doTick(ServerLevel level, T entity) {
                wrapperLogic.tick(level, entity);
            }
        };
    }

    /**
     * Keeps distance keys fixed for one sort while source and target entities move.
     */
    public static <T extends Entity> Comparator<T> distanceComparator(Entity source) {
        var origin = source.position();
        Map<T, Double> distances = new IdentityHashMap<>();
        return Comparator.comparingDouble(target ->
                distances.computeIfAbsent(target, entity -> entity.position().distanceToSqr(origin)));
    }

    @FunctionalInterface
    public interface TickWrapper<T extends LivingEntity> {
        void tick(ServerLevel level, T entity);
    }
}
