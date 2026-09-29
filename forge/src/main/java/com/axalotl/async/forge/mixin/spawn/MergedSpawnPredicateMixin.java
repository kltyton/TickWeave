package com.axalotl.async.forge.mixin.spawn;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps Forge's shared mod spawn predicates safe while chunk spawn jobs remain parallel. */
@Mixin(value = SpawnPlacementRegisterEvent.MergedSpawnPredicate.class, remap = false)
public abstract class MergedSpawnPredicateMixin<T extends Entity> {
    @Unique private static final Object tickweave$spawnPredicateLock = new Object();

    @ModifyReturnValue(method = "build", at = @At("RETURN"), remap = false)
    private SpawnPlacements.SpawnPredicate<T> tickweave$serializeModPredicates(
            SpawnPlacements.SpawnPredicate<T> predicate) {
        return (type, level, reason, position, random) -> {
            synchronized (tickweave$spawnPredicateLock) {
                return predicate.test(type, level, reason, position, random);
            }
        };
    }
}
