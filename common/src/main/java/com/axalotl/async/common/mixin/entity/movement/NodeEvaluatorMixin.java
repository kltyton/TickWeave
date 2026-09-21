package com.axalotl.async.common.mixin.entity.movement;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NodeEvaluator.class)
public abstract class NodeEvaluatorMixin {
    @Shadow @Final
    protected Int2ObjectMap<Node> nodes;

    /**
     * @author TickWeave contributors
     * @reason Avoid capturing coordinates when the native node cache already contains the key.
     */
    @Overwrite
    protected Node getNode(int x, int y, int z) {
        int key = Node.createHash(x, y, z);
        if (nodes.getClass() == Int2ObjectOpenHashMap.class) {
            Node existing = nodes.get(key);
            if (existing != nodes.defaultReturnValue() || nodes.containsKey(key)) return existing;
        }
        return nodes.computeIfAbsent(key, ignored -> new Node(x, y, z));
    }
}
