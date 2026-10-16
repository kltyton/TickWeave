package com.axalotl.async.common.bootstrap;

import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/** Keeps per-player chunk-delivery callbacks and their transient send state in one owner transaction. */
public final class ChunkDeliveryExtension implements IExtension {
    private static final String GUARD = "tickweave$chunkDelivery";
    private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String SUFFIX = "$tickweave$delivery";
    private static final Handle FACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

    @Override public boolean checkActive(MixinEnvironment environment) { return true; }
    @Override public void preApply(ITargetClassContext context) {}
    @Override public void export(MixinEnvironment environment, String name, boolean force, ClassNode node) {}

    @Override public void postApply(ITargetClassContext context) {
        ClassNode owner = context.getClassNode();
        MethodNode guard = owner.methods.stream().filter(method -> method.name.equals(GUARD)
                && method.desc.equals("(Ljava/lang/Runnable;)V")).findFirst().orElse(null);
        if (guard == null) return;
        int coordinated = 0;
        for (MethodNode method : List.copyOf(owner.methods)) {
            if ((method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != Opcodes.ACC_PRIVATE
                    || method.name.endsWith(SUFFIX)
                    || !(method.desc.equals("()V") || method.desc.equals("(" + CALLBACK + ")V"))
                    || !deliversChunks(owner, method)) continue;
            String original = method.name;
            method.name += SUFFIX;
            MethodNode wrapper = new MethodNode(Opcodes.ASM9, method.access, original, method.desc, null, null);
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            Type[] arguments = Type.getArgumentTypes(method.desc);
            for (int i = 0; i < arguments.length; i++) wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, i + 1));
            String captures = "(L" + owner.name + ";" + method.desc.substring(1, method.desc.indexOf(')')) + ")Ljava/lang/Runnable;";
            wrapper.instructions.add(new InvokeDynamicInsnNode("run", captures, FACTORY, Type.getMethodType("()V"),
                    new Handle(Opcodes.H_INVOKESPECIAL, owner.name, method.name, method.desc, false), Type.getMethodType("()V")));
            wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.name, guard.name, guard.desc, false));
            wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
            wrapper.maxLocals = arguments.length + 1;
            wrapper.maxStack = arguments.length + 2;
            owner.methods.add(wrapper);
            coordinated++;
        }
        if (coordinated != 0) LogManager.getLogger("TickWeave").info(
                "Coordinated {} native chunk-delivery callbacks in {}", coordinated, owner.name);
    }

    private static boolean deliversChunks(ClassNode owner, MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.startsWith("net/minecraft/") || call.owner.equals(owner.name)
                    || Type.getReturnType(call.desc).getSort() != Type.VOID) continue;
            Type[] arguments = Type.getArgumentTypes(call.desc);
            if (arguments.length == 3 && arguments[0].equals(Type.getObjectType(owner.name))
                    && arguments[1].getDescriptor().equals("Lorg/apache/commons/lang3/mutable/MutableObject;")
                    && arguments[2].getSort() == Type.OBJECT
                    && arguments[2].getInternalName().startsWith("net/minecraft/")) return true;
        }
        return false;
    }
}
