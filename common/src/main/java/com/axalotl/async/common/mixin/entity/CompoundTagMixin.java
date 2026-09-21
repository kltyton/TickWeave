package com.axalotl.async.common.mixin.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Protects shared tag storage; multi-call read/modify/write sequences still require caller ownership. */
@Mixin(value = CompoundTag.class, priority = 500)
public abstract class CompoundTagMixin {
    @Shadow @Final @Mutable private Map<String, Tag> tags;
    @Unique private static final ThreadLocal<Deque<Map<String, Tag>>> tickweave$snapshots =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void tickweave$concurrentTags(CallbackInfo ci) {
        if (!(tags instanceof ConcurrentHashMap)) tags = new ConcurrentHashMap<>(tags);
    }

    @WrapMethod(method = "write")
    private void tickweave$writeSnapshot(DataOutput output, Operation<Void> original) throws IOException {
        Deque<Map<String, Tag>> snapshots = tickweave$snapshots.get();
        snapshots.push(Map.copyOf(tags));
        try {
            original.call(output);
        } finally {
            snapshots.pop();
            if (snapshots.isEmpty()) tickweave$snapshots.remove();
        }
    }

    @WrapMethod(method = "merge")
    private CompoundTag tickweave$mergeSnapshot(CompoundTag source, Operation<CompoundTag> original) {
        Deque<Map<String, Tag>> snapshots = tickweave$snapshots.get();
        snapshots.push(Map.copyOf(((CompoundTagMixin) (Object) source).tags));
        try {
            return original.call(source);
        } finally {
            snapshots.pop();
            if (snapshots.isEmpty()) tickweave$snapshots.remove();
        }
    }

    @ModifyExpressionValue(method = {"write", "merge"}, at = @At(value = "FIELD",
            target = "Lnet/minecraft/nbt/CompoundTag;tags:Ljava/util/Map;"))
    private Map<String, Tag> tickweave$stableEntries(Map<String, Tag> live) {
        Map<String, Tag> snapshot = tickweave$snapshots.get().peek();
        return snapshot == null ? live : snapshot;
    }

    @ModifyExpressionValue(method = {"getByte", "getShort", "getInt", "getLong", "getFloat", "getDouble"},
            at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object tickweave$removedNumber(Object value) {
        return value == null ? LongTag.valueOf(0) : value;
    }

    @ModifyExpressionValue(method = "getString", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object tickweave$removedString(Object value) {
        return value == null ? StringTag.valueOf("") : value;
    }

    @ModifyExpressionValue(method = "getByteArray", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object tickweave$removedBytes(Object value) {
        return value == null ? new ByteArrayTag(new byte[0]) : value;
    }

    @ModifyExpressionValue(method = "getIntArray", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object tickweave$removedInts(Object value) {
        return value == null ? new IntArrayTag(new int[0]) : value;
    }

    @ModifyExpressionValue(method = "getLongArray", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object tickweave$removedLongs(Object value) {
        return value == null ? new LongArrayTag(new long[0]) : value;
    }

    @ModifyExpressionValue(method = "getCompound", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object tickweave$removedCompound(Object value) {
        return value == null ? new CompoundTag() : value;
    }

    @ModifyExpressionValue(method = "getList", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object tickweave$removedList(Object value) {
        return value == null ? new ListTag() : value;
    }
}
