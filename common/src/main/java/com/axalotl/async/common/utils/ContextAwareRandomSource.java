package com.axalotl.async.common.utils;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.jetbrains.annotations.NotNull;

public class ContextAwareRandomSource implements RandomSource {
    private final RandomSource              mainThreadRandom;
    private final ThreadLocal<RandomSource> asyncRandom = ThreadLocal.withInitial(RandomSource::create);

    public ContextAwareRandomSource(RandomSource mainThreadRandom) { this.mainThreadRandom = mainThreadRandom; }

    private RandomSource get() {
        if (Thread.currentThread().getName().contains("Async-Tick-Pool")) return asyncRandom.get();
        return mainThreadRandom;
    }

    @Override @NotNull public RandomSource fork()                      { return get().fork(); }
    @Override @NotNull public PositionalRandomFactory forkPositional() { return get().forkPositional(); }

    @Override public void setSeed(long seed)                           { get().setSeed(seed); }
    @Override public int nextInt()                                     { return get().nextInt(); }
    @Override public int nextInt(int bound)                            { return get().nextInt(bound); }
    @Override public long nextLong()                                   { return get().nextLong(); }
    @Override public boolean nextBoolean()                             { return get().nextBoolean(); }
    @Override public float nextFloat()                                 { return get().nextFloat(); }
    @Override public double nextDouble()                               { return get().nextDouble(); }
    @Override public double nextGaussian()                             { return get().nextGaussian(); }
}