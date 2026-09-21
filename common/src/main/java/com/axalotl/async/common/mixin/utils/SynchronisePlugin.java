package com.axalotl.async.common.mixin.utils;

import com.axalotl.async.common.AsyncCommon;
import com.axalotl.async.common.bootstrap.SharedWeakMapExtension;
import com.axalotl.async.common.platform.PlatformUtils;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class SynchronisePlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int FINAL_STATIC_PRIVATE_ABSTRACT = 0x1548; // final, static, private, abstract
    private static final int SYNCHRONIZED = 0x20; // synchronized
    private final Multimap<String, String> mixin2MethodsMap = ArrayListMultimap.create();
    private final Multimap<String, String> mixin2MethodsExcludeMap = ArrayListMultimap.create();
    private final TreeSet<String> syncAllSet = new TreeSet<>();

    @Override
    public void onLoad(String mixinPackage) {
        SharedWeakMapExtension.register();
        mixin2MethodsExcludeMap.put("com.axalotl.async.common.mixin.utils.SyncAllMixin", "net.minecraft.world.level.chunk.ChunkStatus.isOrAfter");
        syncAllSet.add("com.axalotl.async.common.mixin.utils.FastUtilsMixin");
        syncAllSet.add("com.axalotl.async.common.mixin.utils.SyncAllMixin");
        syncAllSet.add("com.axalotl.async.common.mixin.compat.SophisticatedCoreSlotValueMapMixin");
    }

    @Override
    public String getRefMapperConfig() {
        return PlatformUtils.platformUsesRefmap() ? "tickweave.refmap.json" : null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".worldgen.")) {
            return !PlatformUtils.isModLoaded("c2me") && !PlatformUtils.isModLoaded("c2me-opts-math")
                    && !PlatformUtils.isModLoaded("noisium") && !PlatformUtils.isModLoaded("harichunk");
        }
        if (mixinClassName.endsWith(".compat.BlueprintEntityMixin")) {
            return PlatformUtils.isModLoaded("blueprint");
        }
        if (mixinClassName.contains(".compat.SophisticatedCore")) {
            return PlatformUtils.isModLoaded("sophisticatedcore");
        }
        if (mixinClassName.endsWith(".lithium.RadiumServerLevel")) {
            return AsyncCommon.LITHIUM;
        }
        if (mixinClassName.endsWith(".vmp.VMPChunkMapMixin")) {
            return AsyncCommon.HARIPLAYER;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.equals("com.axalotl.async.forge.mixin.entity.PersistentDataMixin")) {
            var field = targetClass.fields.stream().filter(candidate -> candidate.name.equals("persistentData")
                    && candidate.desc.equals("Lnet/minecraft/nbt/CompoundTag;")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing Forge entity persistentData field"));
            if ((field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) != 0) {
                throw new IllegalStateException("Unexpected Forge entity persistentData field flags: " + field.access);
            }
            field.access |= Opcodes.ACC_VOLATILE;
        }
        if (mixinClassName.equals("com.axalotl.async.forge.mixin.utils.LazyOptionalMixin")) {
            deferOptionalListeners(targetClass);
        }
        Collection<String> targetMethods = mixin2MethodsMap.get(mixinClassName);
        Collection<String> excludedMethods = mixin2MethodsExcludeMap.get(mixinClassName);

        if (!targetMethods.isEmpty()) {
            applySynchronizeBit(targetClass, targetMethods, targetClassName);
        } else if (syncAllSet.contains(mixinClassName)) {
            for (MethodNode method : targetClass.methods) {
                if ((method.access & FINAL_STATIC_PRIVATE_ABSTRACT) == 0 && !method.name.equals("<init>") && !excludedMethods.contains(method.name)) {
                    // When HariChunk/C2ME is loaded, skip adding synchronized to DynamicGraphMinFixedPoint
                    // because C2ME has its own lighting thread management that handles concurrency
                    if (AsyncCommon.HARICHUNK && targetClassName.contains("DynamicGraphMinFixedPoint")) {
                        LOGGER.debug("Skipping synchronized for {} in {} - C2ME manages lighting threads", method.name, targetClassName);
                        continue;
                    }
                    // When Harium is loaded, skip adding synchronized to PalettedContainer's lock/unlock
                    // because Harium already overwrites these to no-op
                    if (AsyncCommon.LITHIUM && targetClassName.contains("PalettedContainer")
                            && (method.name.equals("lock") || method.name.equals("unlock"))) {
                        LOGGER.debug("Skipping synchronized for {} in {} - Harium already handles locking", method.name, targetClassName);
                        continue;
                    }
                    method.access |= SYNCHRONIZED;
                    logSynchronize(method.name, targetClassName, mixinClassName);
                }
            }
        }
    }

    private static void deferOptionalListeners(ClassNode targetClass) {
        int deferred = 0;
        for (MethodNode method : targetClass.methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                        || !field.owner.equals(targetClass.name) || !field.name.equals("listeners")
                        || !field.desc.equals("Ljava/util/Set;")) continue;
                AbstractInsnNode init = previousInstruction(field);
                AbstractInsnNode duplicate = previousInstruction(init);
                AbstractInsnNode allocation = previousInstruction(duplicate);
                if (!(init instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !call.owner.equals("java/util/HashSet") || !call.name.equals("<init>")
                        || !call.desc.equals("()V") || duplicate == null || duplicate.getOpcode() != Opcodes.DUP
                        || !(allocation instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
                        || !type.desc.equals("java/util/HashSet")) continue;
                // The companion Mixin materializes the private set at its first add operation.
                method.instructions.remove(allocation);
                method.instructions.remove(duplicate);
                method.instructions.remove(init);
                method.instructions.insertBefore(field, new InsnNode(Opcodes.ACONST_NULL));
                deferred++;
            }
        }
        if (deferred != 1) throw new IllegalStateException("Unexpected LazyOptional listener initializer count: " + deferred);
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        do { instruction = instruction.getPrevious(); }
        while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private void applySynchronizeBit(ClassNode targetClass, Collection<String> targetMethods, String targetClassName) {
        for (MethodNode method : targetClass.methods) {
            for (String targetMethod : targetMethods) {
                if (method.name.equals(targetMethod)) {
                    method.access |= SYNCHRONIZED;
                    logSynchronize(method.name, targetClassName, null);
                }
            }
        }
    }

    private void logSynchronize(String methodName, String targetClassName, String mixinClassName) {
        if (mixinClassName == null || !mixinClassName.equals("com.axalotl.async.mixin.utils.FastUtilsMixin")) {
            String message = "Setting synchronize bit for " + methodName + " in " + targetClassName + ".";
            LOGGER.debug(message);
        }
    }
}
