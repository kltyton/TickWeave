package com.axalotl.async.common.bootstrap;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Avoids a pristine empty map that our constructor hook immediately replaces. */
public final class ConcurrentTagConstructor {
    private static final String MAP = "Ljava/util/Map;";
    private static final String CONCURRENT = "java/util/concurrent/ConcurrentHashMap";
    private static final Set<String> EMPTY_MAPS = Set.of("java/util/HashMap", "java/util/LinkedHashMap",
            CONCURRENT, "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap");

    private ConcurrentTagConstructor() {}

    public static boolean optimize(ClassNode owner) {
        MethodNode constructor = method(owner, "<init>", "(" + MAP + ")V");
        MethodNode empty = method(owner, "<init>", "()V");
        if (constructor == null || empty == null || !constructor.tryCatchBlocks.isEmpty()
                || !empty.tryCatchBlocks.isEmpty() || !onlyConcurrentStore(owner, constructor)) return false;
        List<AbstractInsnNode> code = BytecodeInstructions.opcodes(empty);
        int end = code.size() - 2;
        if (end < 2 || !load(code.get(0), 0)
                || !call(code.get(end), Opcodes.INVOKESPECIAL, owner.name, "<init>", "(" + MAP + ")V")
                || code.get(end + 1).getOpcode() != Opcodes.RETURN) return false;
        if (end == 4 && type(code.get(1), Opcodes.NEW, CONCURRENT)) return false;
        boolean pure = end == 2 && emptyFactory(owner, code.get(1), false)
                || end == 4 && emptyAllocation(code.subList(1, 4));
        if (end == 3) pure |= harmlessInput(owner, code.get(1)) && emptyFactory(owner, code.get(2), true);
        if (end == 5 && code.get(2) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE
                && store.var != 0 && load(code.get(3), store.var)) {
            pure |= harmlessInput(owner, code.get(1)) && emptyFactory(owner, code.get(4), true);
        }
        if (!pure) return false;
        InsnList replacement = new InsnList();
        replacement.add(new TypeInsnNode(Opcodes.NEW, CONCURRENT));
        replacement.add(new InsnNode(Opcodes.DUP));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CONCURRENT, "<init>", "()V", false));
        empty.instructions.insertBefore(code.get(1), replacement);
        for (int i = 1; i < end; i++) empty.instructions.remove(code.get(i));
        for (AbstractInsnNode instruction : empty.instructions.toArray()) {
            if (instruction instanceof FrameNode) empty.instructions.remove(instruction);
        }
        empty.maxStack = Math.max(empty.maxStack, 3);
        return true;
    }

    private static boolean onlyConcurrentStore(ClassNode owner, MethodNode constructor) {
        List<AbstractInsnNode> code = BytecodeInstructions.opcodes(constructor);
        if (code.size() != 9 || !load(code.get(0), 0)
                || !call(code.get(1), Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V")
                || !load(code.get(2), 0) || !load(code.get(3), 1)
                || !(code.get(4) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                || !field.owner.equals(owner.name) || !field.desc.equals(MAP)
                || !load(code.get(5), 0) || code.get(6).getOpcode() != Opcodes.ACONST_NULL
                || !(code.get(7) instanceof MethodInsnNode hook) || hook.getOpcode() != Opcodes.INVOKESPECIAL
                || !hook.owner.equals(owner.name) || !hook.name.endsWith("$tickweave$concurrentTags")
                || !hook.desc.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
                || code.get(8).getOpcode() != Opcodes.RETURN) return false;
        MethodNode handler = method(owner, hook.name, hook.desc);
        if (handler == null || (handler.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED)) != 0
                || !handler.tryCatchBlocks.isEmpty()) return false;
        List<AbstractInsnNode> body = BytecodeInstructions.opcodes(handler);
        return body.size() == 12 && load(body.get(0), 0) && field(body.get(1), Opcodes.GETFIELD, field)
                && type(body.get(2), Opcodes.INSTANCEOF, CONCURRENT)
                && body.get(3) instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE
                && next(jump.label) == body.get(11)
                && load(body.get(4), 0) && type(body.get(5), Opcodes.NEW, CONCURRENT)
                && body.get(6).getOpcode() == Opcodes.DUP && load(body.get(7), 0)
                && field(body.get(8), Opcodes.GETFIELD, field)
                && call(body.get(9), Opcodes.INVOKESPECIAL, CONCURRENT, "<init>", "(" + MAP + ")V")
                && field(body.get(10), Opcodes.PUTFIELD, field) && body.get(11).getOpcode() == Opcodes.RETURN;
    }

    private static boolean harmlessInput(ClassNode owner, AbstractInsnNode instruction) {
        if (emptyFactory(owner, instruction, false)) return true;
        if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                || !call.owner.equals(owner.name) || !call.desc.equals("()Ljava/util/HashMap;")) return false;
        MethodNode factory = method(owner, call.name, call.desc);
        if (!privateFactory(factory)) return false;
        List<AbstractInsnNode> body = BytecodeInstructions.opcodes(factory);
        return body.size() == 2 && body.get(0).getOpcode() == Opcodes.ACONST_NULL
                && body.get(1).getOpcode() == Opcodes.ARETURN;
    }

    private static boolean emptyFactory(ClassNode owner, AbstractInsnNode instruction, boolean input) {
        if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) return false;
        if (!input && call.owner.equals("com/google/common/collect/Maps") && call.name.equals("newHashMap")
                && call.desc.equals("()Ljava/util/HashMap;")) return true;
        if (!call.owner.equals(owner.name) || !(call.desc.equals((input ? "(" + MAP + ")" : "()") + MAP)
                || !input && call.desc.equals("()Ljava/util/HashMap;"))) return false;
        MethodNode factory = method(owner, call.name, call.desc);
        if (!privateFactory(factory)) return false;
        List<AbstractInsnNode> body = BytecodeInstructions.opcodes(factory);
        return body.size() == 4 && emptyAllocation(body.subList(0, 3)) && body.get(3).getOpcode() == Opcodes.ARETURN;
    }

    private static boolean privateFactory(MethodNode method) {
        return method != null && (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED))
                == (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC) && method.tryCatchBlocks.isEmpty();
    }

    private static boolean emptyAllocation(List<AbstractInsnNode> code) {
        return code.get(0) instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                && EMPTY_MAPS.contains(allocation.desc) && code.get(1).getOpcode() == Opcodes.DUP
                && call(code.get(2), Opcodes.INVOKESPECIAL, allocation.desc, "<init>", "()V");
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst().orElse(null);
    }

    private static boolean load(AbstractInsnNode instruction, int local) {
        return instruction instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD && variable.var == local;
    }

    private static boolean type(AbstractInsnNode instruction, int opcode, String name) {
        return instruction instanceof TypeInsnNode type && type.getOpcode() == opcode && type.desc.equals(name);
    }

    private static boolean field(AbstractInsnNode instruction, int opcode, FieldInsnNode expected) {
        return instruction instanceof FieldInsnNode field && field.getOpcode() == opcode && field.owner.equals(expected.owner)
                && field.name.equals(expected.name) && field.desc.equals(expected.desc);
    }

    private static boolean call(AbstractInsnNode instruction, int opcode, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && call.owner.equals(owner)
                && call.name.equals(name) && call.desc.equals(descriptor);
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
}
