package com.axalotl.async.bootstrap.collections;

import java.io.IOException;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/** Accepts cache factories that only initialize the new object's own fields. */
final class CacheConstructor {
    private final CollectionSources sources;

    CacheConstructor(CollectionSources sources) { this.sources = sources; }

    boolean accepts(Handle factory, String initializedOwner, String keyType) throws IOException {
        if (factory.getTag() == Opcodes.H_NEWINVOKESPECIAL && factory.getName().equals("<init>")
                && factory.getDesc().equals("(L" + keyType + ";)V"))
            return accepts(factory.getOwner(), factory.getDesc(), initializedOwner, new HashSet<>());
        if (factory.getTag() != Opcodes.H_INVOKESTATIC || !factory.getOwner().equals(initializedOwner)
                || Type.getArgumentTypes(factory.getDesc()).length != 1
                || !Type.getArgumentTypes(factory.getDesc())[0].equals(Type.getObjectType(keyType))) return false;
        var owner = sources.read(factory.getOwner());
        if (owner == null) return false;
        var method = owner.methods.stream().filter(value -> value.name.equals(factory.getName())
                && value.desc.equals(factory.getDesc())).findFirst().orElse(null);
        if (method == null || !method.tryCatchBlocks.isEmpty()) return false;
        var code = CollectionPlan.boundedInstructions(method);
        if (code == null || code.size() != 4) return false;
        if (code.get(0) instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                && code.get(1).getOpcode() == Opcodes.DUP && code.get(2) instanceof MethodInsnNode constructor
                && constructor.getOpcode() == Opcodes.INVOKESPECIAL && constructor.owner.equals(allocation.desc)
                && constructor.name.equals("<init>") && constructor.desc.equals("()V")
                && code.get(3).getOpcode() == Opcodes.ARETURN)
            return accepts(allocation.desc, "()V", initializedOwner, new HashSet<>());
        return keyType.equals("java/lang/String") && code.get(0) instanceof VarInsnNode argument
                && argument.getOpcode() == Opcodes.ALOAD && argument.var == 0
                && code.get(1) instanceof MethodInsnNode bytes && bytes.getOpcode() == Opcodes.INVOKEVIRTUAL
                && bytes.owner.equals("java/lang/String") && bytes.name.equals("getBytes") && bytes.desc.equals("()[B")
                && code.get(2) instanceof MethodInsnNode uuid && uuid.getOpcode() == Opcodes.INVOKESTATIC
                && uuid.owner.equals("java/util/UUID") && uuid.name.equals("nameUUIDFromBytes")
                && uuid.desc.equals("([B)Ljava/util/UUID;") && code.get(3).getOpcode() == Opcodes.ARETURN;
    }

    private boolean accepts(String owner, String descriptor, String initializedOwner, Set<String> visiting)
            throws IOException {
        if (owner.equals("java/lang/Object")) return descriptor.equals("()V");
        if (!visiting.add(owner + descriptor)) return false;
        try {
            var node = sources.read(owner);
            if (node == null) return false;
            // Superclasses of the cache holder have already completed initialization before its method runs.
            if (!sources.subtype(initializedOwner, owner)
                    && (!node.interfaces.isEmpty()
                    || node.methods.stream().anyMatch(method -> method.name.equals("<clinit>")))) return false;
            var constructor = node.methods.stream().filter(method -> method.name.equals("<init>")
                    && method.desc.equals(descriptor)).findFirst().orElse(null);
            if (constructor == null || !constructor.tryCatchBlocks.isEmpty()) return false;
            org.objectweb.asm.tree.analysis.Frame<SourceValue>[] frames;
            try {
                frames = new Analyzer<>(new ThisOrigins()).analyze(owner, constructor);
            } catch (AnalyzerException failure) {
                return false;
            }
            var positions = new IdentityHashMap<AbstractInsnNode, Integer>();
            int position = 0;
            for (var instruction : constructor.instructions) positions.put(instruction, position++);
            for (var instruction : constructor.instructions) {
                int opcode = instruction.getOpcode();
                if (opcode < 0) continue;
                var frame = frames[positions.get(instruction)];
                if (frame == null) return false;
                if (instruction instanceof FieldInsnNode field) {
                    if (opcode != Opcodes.PUTFIELD || !sources.subtype(owner, field.owner)
                            || !isThis(frame.getStack(frame.getStackSize() - 2))) return false;
                } else if (instruction instanceof MethodInsnNode call) {
                    int arguments = Type.getArgumentTypes(call.desc).length;
                    if (opcode != Opcodes.INVOKESPECIAL || !call.name.equals("<init>")
                            || !(call.owner.equals(owner) || call.owner.equals(node.superName))
                            || !isThis(frame.getStack(frame.getStackSize() - arguments - 1))
                            || !accepts(call.owner, call.desc, initializedOwner, visiting)) return false;
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    if (!dynamic.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")
                            || !(dynamic.bsm.getName().equals("makeConcat")
                            || dynamic.bsm.getName().equals("makeConcatWithConstants"))) return false;
                    for (Type argument : Type.getArgumentTypes(dynamic.desc)) {
                        if (argument.getSort() == Type.ARRAY || argument.getSort() == Type.OBJECT
                                && !argument.getInternalName().equals("java/lang/String")) return false;
                    }
                    for (Object constant : dynamic.bsmArgs)
                        if (!(constant instanceof String || constant instanceof Number)) return false;
                } else if (instruction instanceof LdcInsnNode constant) {
                    if (!(constant.cst instanceof String || constant.cst instanceof Number)) return false;
                } else if (!(opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD)
                        && !(opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.LDC)
                        && opcode != Opcodes.RETURN && opcode != Opcodes.NOP) {
                    return false;
                }
            }
            return true;
        } finally {
            visiting.remove(owner + descriptor);
        }
    }

    private static boolean isThis(SourceValue value) {
        return value != null && !value.insns.isEmpty() && value.insns.stream().allMatch(instruction ->
                instruction instanceof VarInsnNode variable && instruction.getOpcode() == Opcodes.ALOAD
                        && variable.var == 0);
    }

    private static final class ThisOrigins extends SourceInterpreter {
        ThisOrigins() { super(Opcodes.ASM9); }
        @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) {
            return instruction instanceof VarInsnNode variable && instruction.getOpcode() == Opcodes.ALOAD
                    && variable.var == 0 ? new SourceValue(1, instruction) : value;
        }
    }
}
