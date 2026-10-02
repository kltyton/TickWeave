package com.axalotl.async.common.mixin.accessor;

import net.minecraft.world.entity.animal.Turtle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value={Turtle.class})
public interface TurtleAccessor {
    @Invoker(value="setHasEgg")
    public void invokeSetHasEgg(boolean var1);
}

