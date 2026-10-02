package com.axalotl.async.common.mixin.world;

import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value={Explosion.class})
public interface ExplosionAccessor {
    @Accessor(value="level")
    public Level getLevel();
}

