package com.axalotl.async.common.bootstrap;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

public final class SharedWeakMapExtension implements IExtension {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int CACHE_FLAGS = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;

    public static void register() {
        MixinExtrasBootstrap.init();
        Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        if (!(transformer instanceof IMixinTransformer mixinTransformer)
                || !(mixinTransformer.getExtensions() instanceof Extensions extensions)) {
            throw new IllegalStateException("TickWeave requires the Mixin extension registry");
        }
        if (extensions.getExtensions().stream().noneMatch(SharedWeakMapExtension.class::isInstance)) {
            extensions.add(new SharedWeakMapExtension());
        }
        if (extensions.getExtensions().stream().noneMatch(CollisionFilterExtension.class::isInstance)) {
            extensions.add(new CollisionFilterExtension());
        }
        if (extensions.getExtensions().stream().noneMatch(DeferredCallbackExtension.class::isInstance)) {
            extensions.add(new DeferredCallbackExtension());
        }
        if (extensions.getExtensions().stream().noneMatch(SynchronizedForwarderExtension.class::isInstance)) {
            extensions.add(new SynchronizedForwarderExtension());
        }
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
    }

    @Override
    public void postApply(ITargetClassContext context) {
        ClassNode owner = context.getClassNode();
        protectInitializers(owner);
        if (ConcurrentTagConstructor.optimize(owner)) LOGGER.debug("Avoided empty NBT map replacement in {}", owner.name);
    }

    public static int protectInitializers(ClassNode targetClass) {
        int wrapped = 0;
        for (var method : targetClass.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (var instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTSTATIC
                        || !field.owner.equals(targetClass.name) || !field.desc.equals("Ljava/util/Map;")) continue;
                boolean privateCache = targetClass.fields.stream().anyMatch(declaration ->
                        declaration.name.equals(field.name) && declaration.desc.equals(field.desc)
                                && (declaration.access & CACHE_FLAGS) == CACHE_FLAGS);
                if (!privateCache) continue;
                var previous = field.getPrevious();
                while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
                if (previous instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && call.owner.equals("java/util/WeakHashMap") && call.name.equals("<init>")) {
                    // A wrapper retains weak-key equality and nullable values, unlike ConcurrentHashMap.
                    method.instructions.insertBefore(field, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "java/util/Collections", "synchronizedMap", "(Ljava/util/Map;)Ljava/util/Map;", false));
                    wrapped++;
                }
            }
        }
        if (wrapped != 0) LOGGER.debug("Protected {} shared weak maps in {}", wrapped, targetClass.name);
        return wrapped;
    }

    @Override
    public void export(MixinEnvironment environment, String name, boolean force, ClassNode classNode) {
    }
}
