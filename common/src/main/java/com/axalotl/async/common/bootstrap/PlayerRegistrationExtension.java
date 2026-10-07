package com.axalotl.async.common.bootstrap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/** Moves only native player-registration transactions; tracking callbacks retain their caller. */
public final class PlayerRegistrationExtension implements IExtension {
    private static final String RESULT = "[Ljava/lang/Object;";
    private static final String SUPPLIER = "Ljava/util/function/Supplier;";
    private static final Handle FACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

    @Override public boolean checkActive(MixinEnvironment environment) { return true; }
    @Override public void preApply(ITargetClassContext context) {}
    @Override public void export(MixinEnvironment environment, String name, boolean force, ClassNode node) {}

    @Override public void postApply(ITargetClassContext context) { apply(context.getClassNode()); }

    public static void apply(ClassNode owner) {
        MethodNode moveGuard = guard(owner, "tickweave$moveRegistration");
        if (moveGuard == null) return;
        MethodNode statusGuard = guard(owner, "tickweave$statusRegistration");
        if (statusGuard == null) throw new IllegalStateException("Missing player status transaction in " + owner.name);
        String player = Type.getArgumentTypes(moveGuard.desc)[0].getDescriptor();
        MethodNode move = null, status = null;
        MethodInsnNode moveEnd = null, statusPosition = null;
        for (MethodNode method : owner.methods) {
            if (method.desc.equals("(" + player + ")V")) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.desc.equals("(JJ" + player + ")V")) {
                        if (move != null) throw new IllegalStateException("Ambiguous player move transaction in " + owner.name);
                        move = method;
                        moveEnd = call;
                    }
                }
            } else if (method.desc.equals("(" + player + "Z)V")) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(owner.name)
                            && call.desc.startsWith("(" + player + ")L")) {
                        if (status != null) throw new IllegalStateException("Ambiguous player status transaction in " + owner.name);
                        status = method;
                        statusPosition = call;
                    }
                }
            }
        }
        if (move == null || status == null) throw new IllegalStateException("Missing native player registration boundaries in " + owner.name);
        AbstractInsnNode start = null;
        for (AbstractInsnNode instruction = move.instructions.getFirst(); instruction != moveEnd; instruction = instruction.getNext()) {
            if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO
                    && move.instructions.indexOf(jump.label) < move.instructions.indexOf(jump)) {
                // Leave the tracking loop and its exit label in the caller, independent of getter owners.
                start = next(jump);
            }
        }
        if (start == null) throw new IllegalStateException("Missing tracking loop before move registration in " + owner.name);
        AbstractInsnNode statusEnd = null;
        for (AbstractInsnNode instruction = statusPosition.getNext(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field && field.owner.equals(owner.name) && field.desc.equals("I")) {
                statusEnd = previous(previous(field));
                break;
            }
        }
        if (!(statusEnd instanceof VarInsnNode statusLoad) || statusLoad.getOpcode() != Opcodes.ILOAD)
            throw new IllegalStateException("Missing tracking loop after status registration in " + owner.name);
        split(owner, move, start, next(moveEnd), moveGuard, true);
        split(owner, status, status.instructions.getFirst(), statusEnd, statusGuard, false);
    }

    private static void split(ClassNode owner, MethodNode method, AbstractInsnNode start, AbstractInsnNode end,
                              MethodNode guard, boolean discardRemoved) {
        Set<AbstractInsnNode> region = new HashSet<>();
        Set<Integer> written = new HashSet<>();
        for (AbstractInsnNode instruction = start; instruction != end; instruction = instruction.getNext()) {
            if (instruction == null) throw new IllegalStateException("Unclosed player transaction in " + owner.name);
            region.add(instruction);
            if (instruction instanceof VarInsnNode var && var.getOpcode() >= Opcodes.ISTORE && var.getOpcode() <= Opcodes.ASTORE)
                written.add(var.var);
            if (instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN)
                throw new IllegalStateException("Early return inside player transaction in " + owner.name);
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump && region.contains(instruction) != region.contains(jump.label))
                throw new IllegalStateException("Crossing player transaction branch in " + owner.name);
            if (region.contains(instruction) && (instruction instanceof LookupSwitchInsnNode || instruction instanceof TableSwitchInsnNode))
                throw new IllegalStateException("Unexpected switch inside player transaction in " + owner.name);
        }
        for (TryCatchBlockNode handler : method.tryCatchBlocks) {
            if (region.contains(handler.start) || region.contains(handler.end) || region.contains(handler.handler))
                throw new IllegalStateException("Crossing player transaction exception scope in " + owner.name);
        }
        List<Integer> locals = written.stream().sorted().filter(local -> liveAfter(end, local, new HashSet<>())).toList();
        List<Type> types = new ArrayList<>();
        try {
            var frames = new Analyzer<>(new BasicInterpreter(Opcodes.ASM9) {
                @Override public BasicValue newValue(Type type) {
                    return type != null && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)
                            ? new BasicValue(type) : super.newValue(type);
                }
            }).analyze(owner.name, method);
            var entry = frames[method.instructions.indexOf(start)];
            var frame = frames[method.instructions.indexOf(end)];
            if (entry == null || entry.getStackSize() != 0 || frame == null || frame.getStackSize() != 0)
                throw new IllegalStateException("Nonempty player transaction boundary stack");
            for (int local : locals) {
                Type type = frame.getLocal(local).getType();
                if (type == null) throw new IllegalStateException("Undefined player transaction local " + local);
                types.add(type);
            }
        } catch (AnalyzerException failure) {
            throw new IllegalStateException("Cannot analyze player transaction in " + owner.name, failure);
        }
        String helperName = "tickweave$registration$" + (discardRemoved ? "move" : "status");
        String helperDesc = method.desc.substring(0, method.desc.length() - 1) + RESULT;
        MethodNode helper = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                helperName, helperDesc, null, null);
        for (AbstractInsnNode instruction = start; instruction != end;) {
            AbstractInsnNode following = instruction.getNext();
            method.instructions.remove(instruction);
            if (!(instruction instanceof FrameNode) && !(instruction instanceof LineNumberNode)) helper.instructions.add(instruction);
            instruction = following;
        }
        push(helper.instructions, locals.size());
        helper.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < locals.size(); i++) {
            helper.instructions.add(new InsnNode(Opcodes.DUP));
            push(helper.instructions, i);
            Type type = types.get(i);
            helper.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), locals.get(i)));
            box(helper.instructions, type);
            helper.instructions.add(new InsnNode(Opcodes.AASTORE));
        }
        helper.instructions.add(new InsnNode(Opcodes.ARETURN));
        helper.maxLocals = method.maxLocals;
        helper.maxStack = method.maxStack + 4;
        owner.methods.add(helper);

        InsnList dispatch = new InsnList();
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, 1));
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, 1));
        if (!discardRemoved) dispatch.add(new VarInsnNode(Opcodes.ILOAD, 2));
        String captures = "(L" + owner.name + ";" + method.desc.substring(1, method.desc.length() - 2) + ")" + SUPPLIER;
        dispatch.add(new InvokeDynamicInsnNode("get", captures, FACTORY, Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKESPECIAL, owner.name, helperName, helperDesc, false), Type.getMethodType("()" + RESULT)));
        dispatch.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.name, guard.name, guard.desc, false));
        int array = method.maxLocals++;
        dispatch.add(new VarInsnNode(Opcodes.ASTORE, array));
        if (discardRemoved) {
            LabelNode registered = new LabelNode();
            dispatch.add(new VarInsnNode(Opcodes.ALOAD, array));
            dispatch.add(new JumpInsnNode(Opcodes.IFNONNULL, registered));
            dispatch.add(new InsnNode(Opcodes.RETURN));
            dispatch.add(registered);
        }
        for (int i = 0; i < locals.size(); i++) {
            Type type = types.get(i);
            dispatch.add(new VarInsnNode(Opcodes.ALOAD, array));
            push(dispatch, i);
            dispatch.add(new InsnNode(Opcodes.AALOAD));
            unbox(dispatch, type);
            dispatch.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), locals.get(i)));
        }
        method.instructions.insertBefore(end, dispatch);
        method.maxStack += 5;
        if (method.localVariables != null) method.localVariables.removeIf(local -> region.contains(local.start) || region.contains(local.end));
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FrameNode || instruction instanceof LineNumberNode line && region.contains(line.start))
                method.instructions.remove(instruction);
        }
    }

    private static boolean liveAfter(AbstractInsnNode instruction, int local, Set<AbstractInsnNode> visited) {
        while (instruction != null && visited.add(instruction)) {
            if (instruction instanceof VarInsnNode var && var.var == local)
                return var.getOpcode() >= Opcodes.ILOAD && var.getOpcode() <= Opcodes.ALOAD;
            if (instruction instanceof IincInsnNode increment && increment.var == local) return true;
            if (instruction instanceof JumpInsnNode jump) {
                if (liveAfter(jump.label, local, visited)) return true;
                if (jump.getOpcode() == Opcodes.GOTO) return false;
            }
            if (instruction instanceof TableSwitchInsnNode branch) {
                if (liveAfter(branch.dflt, local, visited)) return true;
                for (LabelNode label : branch.labels) if (liveAfter(label, local, visited)) return true;
                return false;
            }
            if (instruction instanceof LookupSwitchInsnNode branch) {
                if (liveAfter(branch.dflt, local, visited)) return true;
                for (LabelNode label : branch.labels) if (liveAfter(label, local, visited)) return true;
                return false;
            }
            if (instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN || instruction.getOpcode() == Opcodes.ATHROW)
                return false;
            instruction = instruction.getNext();
        }
        return false;
    }

    private static MethodNode guard(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElse(null);
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static void push(InsnList code, int value) { code.add(new LdcInsnNode(value)); }

    private static String wrapper(Type type) {
        return switch (type.getSort()) {
            case Type.INT -> "java/lang/Integer";
            case Type.FLOAT -> "java/lang/Float";
            case Type.LONG -> "java/lang/Long";
            case Type.DOUBLE -> "java/lang/Double";
            default -> throw new IllegalStateException("Unexpected player transaction primitive " + type);
        };
    }

    private static void box(InsnList code, Type type) {
        if (type.getSort() < Type.ARRAY) {
            String wrapper = wrapper(type);
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf", "(" + type.getDescriptor() + ")L" + wrapper + ";", false));
        }
    }

    private static void unbox(InsnList code, Type type) {
        if (type.getSort() >= Type.ARRAY) code.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
        else {
            String wrapper = wrapper(type);
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, wrapper));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, wrapper, type.getClassName() + "Value", "()" + type.getDescriptor(), false));
        }
    }
}
