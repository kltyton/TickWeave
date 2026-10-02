package com.axalotl.async.common.mixin.utils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value={ShufflingList.class})
public abstract class ShufflingListMixin<U>
implements Iterable<U> {
    @Shadow
    protected final List<ShufflingList.WeightedEntry<U>> entries = new CopyOnWriteArrayList<ShufflingList.WeightedEntry<U>>();
}

