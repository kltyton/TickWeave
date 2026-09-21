package com.axalotl.async.common.mixin.entity.movement;

import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.entity.task.EntityTasks;
import com.axalotl.async.common.entity.task.OwnerStream;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Pair;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PoiManager.class)
public abstract class PoiManagerMixin {
    @Unique
    private static <T> Stream<T> tickweave$ownedStream(Supplier<Stream<T>> action) {
        var server = ParallelProcessor.getServer();
        if (server == null || server.isSameThread()) return action.get();
        return OwnerStream.wrap(EntityTasks.onMain(action));
    }

    @WrapMethod(method = {"getInSquare", "getInRange"})
    private Stream<PoiRecord> tickweave$getInSquare(Predicate<Holder<PoiType>> type, BlockPos pos, int radius, PoiManager.Occupancy occupancy, Operation<Stream<PoiRecord>> original) {
        return tickweave$ownedStream(() -> original.call(type, pos, radius, occupancy));
    }

    @WrapMethod(method = {"getInChunk"})
    private Stream<PoiRecord> tickweave$getInChunk(Predicate<Holder<PoiType>> type, ChunkPos pos, PoiManager.Occupancy occupancy, Operation<Stream<PoiRecord>> original) {
        return tickweave$ownedStream(() -> original.call(type, pos, occupancy));
    }

    @WrapMethod(method = {"getCountInRange"})
    private long tickweave$getCountInRange(Predicate<Holder<PoiType>> type, BlockPos pos, int radius, PoiManager.Occupancy occupancy, Operation<Long> original) {
        return EntityTasks.onMain(() -> original.call(type, pos, radius, occupancy));
    }

    @WrapMethod(method = {"findAll"})
    private Stream<BlockPos> tickweave$findAll(Predicate<Holder<PoiType>> type, Predicate<BlockPos> positions, BlockPos pos, int radius, PoiManager.Occupancy occupancy, Operation<Stream<BlockPos>> original) {
        return tickweave$ownedStream(() -> original.call(type, positions, pos, radius, occupancy));
    }

    @WrapMethod(method = {"findAllWithType", "findAllClosestFirstWithType"})
    private Stream<Pair<Holder<PoiType>, BlockPos>> tickweave$findAllWithType(Predicate<Holder<PoiType>> type, Predicate<BlockPos> positions, BlockPos pos, int radius, PoiManager.Occupancy occupancy, Operation<Stream<Pair<Holder<PoiType>, BlockPos>>> original) {
        return tickweave$ownedStream(() -> original.call(type, positions, pos, radius, occupancy));
    }

    @WrapMethod(method = {"find"})
    private Optional<BlockPos> tickweave$find(Predicate<Holder<PoiType>> type, Predicate<BlockPos> positions, BlockPos pos, int radius, PoiManager.Occupancy occupancy, Operation<Optional<BlockPos>> original) {
        return EntityTasks.onMain(() -> original.call(type, positions, pos, radius, occupancy));
    }

    @WrapMethod(method = {"findClosest(Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/Optional;"})
    private Optional<BlockPos> tickweave$findClosest(Predicate<Holder<PoiType>> type, BlockPos pos, int radius, PoiManager.Occupancy occupancy, Operation<Optional<BlockPos>> original) {
        return EntityTasks.onMain(() -> original.call(type, pos, radius, occupancy));
    }

    @WrapMethod(method = {"findClosest(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/Optional;"})
    private Optional<BlockPos> tickweave$findClosest(Predicate<Holder<PoiType>> type, Predicate<BlockPos> positions, BlockPos pos, int radius, PoiManager.Occupancy occupancy, Operation<Optional<BlockPos>> original) {
        return EntityTasks.onMain(() -> original.call(type, positions, pos, radius, occupancy));
    }

    @WrapMethod(method = {"findClosestWithType"})
    private Optional<Pair<Holder<PoiType>, BlockPos>> tickweave$findClosestWithType(Predicate<Holder<PoiType>> type, BlockPos pos, int radius, PoiManager.Occupancy occupancy, Operation<Optional<Pair<Holder<PoiType>, BlockPos>>> original) {
        return EntityTasks.onMain(() -> original.call(type, pos, radius, occupancy));
    }

    @WrapMethod(method = {"take"})
    private Optional<BlockPos> tickweave$take(Predicate<Holder<PoiType>> type, BiPredicate<Holder<PoiType>, BlockPos> positions, BlockPos pos, int radius, Operation<Optional<BlockPos>> original) {
        return EntityTasks.onMain(() -> original.call(type, positions, pos, radius));
    }

    @WrapMethod(method = {"getRandom"})
    private Optional<BlockPos> tickweave$getRandom(Predicate<Holder<PoiType>> type, Predicate<BlockPos> positions, PoiManager.Occupancy occupancy, BlockPos pos, int radius, RandomSource random, Operation<Optional<BlockPos>> original) {
        return EntityTasks.onMain(() -> original.call(type, positions, occupancy, pos, radius, random));
    }

    @WrapMethod(method = {"add"})
    private void tickweave$add(BlockPos pos, Holder<PoiType> type, Operation<Void> original) {
        EntityTasks.onMain(() -> { original.call(pos, type); return null; });
    }

    @WrapMethod(method = {"remove"})
    private void tickweave$remove(BlockPos pos, Operation<Void> original) {
        EntityTasks.onMain(() -> { original.call(pos); return null; });
    }

    @WrapMethod(method = {"release"})
    private boolean tickweave$release(BlockPos pos, Operation<Boolean> original) {
        return EntityTasks.onMain(() -> original.call(pos));
    }

    @WrapMethod(method = {"exists"})
    private boolean tickweave$exists(BlockPos pos, Predicate<Holder<PoiType>> type, Operation<Boolean> original) {
        return EntityTasks.onMain(() -> original.call(pos, type));
    }

    @WrapMethod(method = {"existsAtPosition"})
    private boolean tickweave$existsAtPosition(ResourceKey<PoiType> type, BlockPos pos, Operation<Boolean> original) {
        return EntityTasks.onMain(() -> original.call(type, pos));
    }

    @WrapMethod(method = {"getType"})
    private Optional<Holder<PoiType>> tickweave$getType(BlockPos pos, Operation<Optional<Holder<PoiType>>> original) {
        return EntityTasks.onMain(() -> original.call(pos));
    }

    @WrapMethod(method = {"getFreeTickets"})
    private int tickweave$getFreeTickets(BlockPos pos, Operation<Integer> original) {
        return EntityTasks.onMain(() -> original.call(pos));
    }

    @WrapMethod(method = {"sectionsToVillage"})
    private int tickweave$sectionsToVillage(SectionPos pos, Operation<Integer> original) {
        return EntityTasks.onMain(() -> original.call(pos));
    }

    @WrapMethod(method = {"isVillageCenter"})
    private boolean tickweave$isVillageCenter(long section, Operation<Boolean> original) {
        return EntityTasks.onMain(() -> original.call(section));
    }

    @WrapMethod(method = {"tick"})
    private void tickweave$tick(BooleanSupplier aheadOfTime, Operation<Void> original) {
        EntityTasks.onMain(() -> { original.call(aheadOfTime); return null; });
    }

    @WrapMethod(method = {"setDirty", "onSectionLoad"})
    private void tickweave$setDirty(long section, Operation<Void> original) {
        EntityTasks.onMain(() -> { original.call(section); return null; });
    }

    @WrapMethod(method = {"checkConsistencyWithBlocks"})
    private void tickweave$checkConsistencyWithBlocks(SectionPos pos, LevelChunkSection section, Operation<Void> original) {
        EntityTasks.onMain(() -> { original.call(pos, section); return null; });
    }

    @WrapMethod(method = {"ensureLoadedAndValid"})
    private void tickweave$ensureLoadedAndValid(LevelReader level, BlockPos pos, int radius, Operation<Void> original) {
        EntityTasks.onMain(() -> { original.call(level, pos, radius); return null; });
    }
}
