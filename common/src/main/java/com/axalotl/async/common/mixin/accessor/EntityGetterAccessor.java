package com.axalotl.async.common.mixin.accessor;

import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityGetter.class)
public interface EntityGetterAccessor {
    @Invoker("getEntityCollisions")
    List<VoxelShape> tickweave$invokeEntityCollisions(Entity source, AABB box);
}
