package com.axalotl.async.common.mixin.entity.movement;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin extends NodeEvaluator {
    @Shadow @Final
    private Long2ObjectMap<BlockPathTypes> pathTypesByPosCache;

    /**
     * @author TickWeave contributors
     * @reason Avoid capturing the evaluator and coordinates on native path-type cache hits.
     */
    @Overwrite
    protected BlockPathTypes getCachedBlockType(Mob mob, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (pathTypesByPosCache.getClass() == Long2ObjectOpenHashMap.class) {
            BlockPathTypes existing = pathTypesByPosCache.get(key);
            if (existing != pathTypesByPosCache.defaultReturnValue() || pathTypesByPosCache.containsKey(key)) return existing;
        }
        return pathTypesByPosCache.computeIfAbsent(key, ignored -> getBlockPathType(level, x, y, z, mob));
    }
}
