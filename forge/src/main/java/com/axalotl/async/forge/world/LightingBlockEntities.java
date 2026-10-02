package com.axalotl.async.forge.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

/** Reads lighting inputs from available chunks without requesting a FULL chunk. */
public final class LightingBlockEntities {
    private static final ThreadLocal<Context> ACTIVE = new ThreadLocal<>();

    private LightingBlockEntities() {}

    public static Scope open(LightChunkGetter source) {
        Context previous = ACTIVE.get();
        BlockGetter world = source.getLevel();
        if (!(world instanceof Level level) || !level.isClientSide) ACTIVE.set(new Context(source, world));
        return new Scope(previous);
    }

    public static boolean appliesTo(BlockGetter world) {
        Context context = ACTIVE.get();
        return context != null && context.world == world;
    }

    public static BlockEntity getBlockEntity(Level world, BlockPos position) {
        if (world.isOutsideBuildHeight(position)) return null;
        Context context = ACTIVE.get();
        LightChunk chunk = context.source.getChunkForLighting(position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null) return null;
        BlockGetter blocks = chunk;
        BlockEntity entity = blocks.getBlockEntity(position);
        if (entity != null || !(chunk instanceof ChunkAccess access)) return entity;

        // Proto chunks can still contain packed block-entity data while lighting is running.
        CompoundTag tag = access.getBlockEntityNbt(position);
        BlockState state = blocks.getBlockState(position);
        if (tag != null && !"DUMMY".equals(tag.getString("id"))) {
            entity = BlockEntity.loadStatic(position, state, tag.copy());
        } else if (state.getBlock() instanceof EntityBlock factory) {
            entity = factory.newBlockEntity(position, state);
        }
        if (entity != null) entity.setLevel(world);
        return entity;
    }

    private record Context(LightChunkGetter source, BlockGetter world) {}

    public static final class Scope implements AutoCloseable {
        private final Context previous;

        private Scope(Context previous) { this.previous = previous; }

        @Override public void close() {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }
}
