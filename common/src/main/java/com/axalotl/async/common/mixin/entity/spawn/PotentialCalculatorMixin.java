package com.axalotl.async.common.mixin.entity.spawn;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.level.PotentialCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value={PotentialCalculator.class})
public class PotentialCalculatorMixin {
    @Shadow
    private final List<PotentialCalculator.PointCharge> charges = new CopyOnWriteArrayList<PotentialCalculator.PointCharge>();
}

