package com.axalotl.async.common.bootstrap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;
import org.spongepowered.asm.util.asm.MixinVerifier;

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
        splitMove(owner, move, start, next(moveEnd), moveGuard);
        splitStatus(owner, status, statusPosition, moveEnd.owner, statusGuard);
    }

    /** Outlines registration predecessors; packet tails and exception handlers stay with the caller. */
    private static void splitStatus(ClassNode owner, MethodNode method, MethodInsnNode position,
                                    String playerMap, MethodNode guard) {
        StatusFlow flow = new StatusFlow(owner, method);
        Frame<BasicValue>[] frames;
        try {
            frames = flow.analyze(owner.name, method);
        } catch (AnalyzerException failure) {
            throw new IllegalStateException("Cannot analyze player status transaction in " + owner.name, failure);
        }
        AbstractInsnNode[] instructions = method.instructions.toArray();
        String player = Type.getArgumentTypes(method.desc)[0].getDescriptor();
        String distanceUpdate = "(" + Type.getReturnType(position.desc).getDescriptor() + player + ")V";
        Set<String> fieldOwners = new HashSet<>();
        for (FieldNode field : owner.fields) {
            Type type = Type.getType(field.desc);
            if (type.getSort() == Type.OBJECT) fieldOwners.add(type.getInternalName());
        }
        Set<Integer> region = new HashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        // Follow normal predecessors so both registration branches share one owner invocation.
        for (int i = 0; i < instructions.length; i++) {
            if (frames[i] != null && instructions[i] instanceof MethodInsnNode call
                    && (call == position || call.owner.equals(playerMap)
                    || !call.owner.equals(owner.name) && fieldOwners.contains(call.owner) && call.desc.equals(distanceUpdate))) {
                pending.add(i);
            }
        }
        while (!pending.isEmpty()) {
            int index = pending.removeFirst();
            if (region.add(index)) pending.addAll(flow.predecessors.get(index));
        }
        if (!region.contains(0)) throw new IllegalStateException("Missing player status transaction entry in " + owner.name);

        List<StatusExit> exits = new ArrayList<>();
        Map<Integer, StatusExit> normalExits = new HashMap<>();
        for (int i = 0; i < instructions.length; i++) {
            if (!region.contains(i)) continue;
            for (int target : flow.successors.get(i)) {
                if (!region.contains(target) && !normalExits.containsKey(target)) {
                    StatusExit exit = statusExit(method, flow, frames, target, exits.size(), false);
                    normalExits.put(target, exit);
                    exits.add(exit);
                }
            }
        }
        Map<TryCatchBlockNode, StatusExit> handlers = new HashMap<>();
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            int first = method.instructions.indexOf(block.start), last = method.instructions.indexOf(block.end);
            boolean intersects = false;
            for (int i = first; i < last; i++) if (region.contains(i) && instructions[i].getOpcode() >= 0) intersects = true;
            if (!intersects) continue;
            int target = method.instructions.indexOf(block.handler);
            if (region.contains(target)) throw new IllegalStateException("Player status handler reenters registration in " + owner.name);
            StatusExit exit = statusExit(method, flow, frames, target, exits.size(), true);
            handlers.put(block, exit);
            exits.add(exit);
        }
        if (normalExits.isEmpty()) throw new IllegalStateException("Missing player status transaction exit in " + owner.name);

        String helperName = "tickweave$registration$status";
        String helperDesc = method.desc.substring(0, method.desc.length() - 1) + RESULT;
        MethodNode helper = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                helperName, helperDesc, null, null);
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode instruction : instructions) {
            if (instruction instanceof LabelNode label) {
                StatusExit exit = normalExits.get(method.instructions.indexOf(label));
                labels.put(label, exit == null ? new LabelNode() : exit.bridge);
            }
        }
        LabelNode[] before = new LabelNode[instructions.length], after = new LabelNode[instructions.length];
        for (int i = 0; i < instructions.length; i++) {
            if (!region.contains(i)) continue;
            helper.instructions.add(before[i] = new LabelNode());
            AbstractInsnNode instruction = instructions[i];
            if (!(instruction instanceof FrameNode) && !(instruction instanceof LineNumberNode))
                helper.instructions.add(instruction.clone(labels));
            helper.instructions.add(after[i] = new LabelNode());
            if (flow.successors.get(i).contains(i + 1) && !region.contains(i + 1))
                helper.instructions.add(new JumpInsnNode(Opcodes.GOTO, normalExits.get(i + 1).bridge));
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            StatusExit exit = handlers.get(block);
            if (exit == null) continue;
            int first = method.instructions.indexOf(block.start), last = method.instructions.indexOf(block.end);
            for (int i = first; i < last;) {
                if (!region.contains(i)) { i++; continue; }
                int begin = i;
                boolean executable = false;
                do {
                    executable |= instructions[i].getOpcode() >= 0;
                    i++;
                } while (i < last && region.contains(i));
                if (executable) helper.tryCatchBlocks.add(new TryCatchBlockNode(before[begin], after[i - 1], exit.bridge, block.type));
            }
        }
        int thrown = method.maxLocals;
        for (StatusExit exit : exits) {
            helper.instructions.add(exit.bridge);
            // Resume the original handler with its live locals and the same throwable on the caller.
            if (exit.handler) helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, thrown));
            push(helper.instructions, exit.locals.size() + 2);
            helper.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            helper.instructions.add(new InsnNode(Opcodes.DUP));
            push(helper.instructions, 0);
            push(helper.instructions, exit.id);
            box(helper.instructions, Type.INT_TYPE);
            helper.instructions.add(new InsnNode(Opcodes.AASTORE));
            if (exit.handler) {
                helper.instructions.add(new InsnNode(Opcodes.DUP));
                push(helper.instructions, 1);
                helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, thrown));
                helper.instructions.add(new InsnNode(Opcodes.AASTORE));
            }
            for (int i = 0; i < exit.locals.size(); i++) {
                helper.instructions.add(new InsnNode(Opcodes.DUP));
                push(helper.instructions, i + 2);
                Type type = exit.types.get(i);
                helper.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), exit.locals.get(i)));
                box(helper.instructions, type);
                helper.instructions.add(new InsnNode(Opcodes.AASTORE));
            }
            helper.instructions.add(new InsnNode(Opcodes.ARETURN));
        }
        helper.maxLocals = method.maxLocals + 1;
        helper.maxStack = method.maxStack + 6;

        InsnList dispatch = new InsnList();
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, 1));
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, 1));
        dispatch.add(new VarInsnNode(Opcodes.ILOAD, 2));
        dispatch.add(new InvokeDynamicInsnNode("get", "(L" + owner.name + ";" + player + "Z)" + SUPPLIER,
                FACTORY, Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKESPECIAL, owner.name, helperName, helperDesc, false), Type.getMethodType("()" + RESULT)));
        dispatch.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.name, guard.name, guard.desc, false));
        int array = method.maxLocals++;
        dispatch.add(new VarInsnNode(Opcodes.ASTORE, array));
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, array));
        push(dispatch, 0);
        dispatch.add(new InsnNode(Opcodes.AALOAD));
        unbox(dispatch, Type.INT_TYPE);
        LabelNode invalid = new LabelNode();
        LabelNode[] routes = exits.stream().map(exit -> new LabelNode()).toArray(LabelNode[]::new);
        dispatch.add(new TableSwitchInsnNode(0, routes.length - 1, invalid, routes));
        for (StatusExit exit : exits) {
            dispatch.add(routes[exit.id]);
            for (int i = 0; i < exit.locals.size(); i++) {
                Type type = exit.types.get(i);
                dispatch.add(new VarInsnNode(Opcodes.ALOAD, array));
                push(dispatch, i + 2);
                dispatch.add(new InsnNode(Opcodes.AALOAD));
                if (type.getSort() == Type.OBJECT && type.getInternalName().equals("null")) {
                    dispatch.add(new InsnNode(Opcodes.POP));
                    dispatch.add(new InsnNode(Opcodes.ACONST_NULL));
                } else unbox(dispatch, type);
                dispatch.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), exit.locals.get(i)));
            }
            if (exit.handler) {
                dispatch.add(new VarInsnNode(Opcodes.ALOAD, array));
                push(dispatch, 1);
                dispatch.add(new InsnNode(Opcodes.AALOAD));
                dispatch.add(new TypeInsnNode(Opcodes.CHECKCAST, frames[exit.target].getStack(0).getType().getInternalName()));
            }
            AbstractInsnNode target = instructions[exit.target];
            LabelNode label;
            if (target instanceof LabelNode original) label = original;
            else {
                label = new LabelNode();
                method.instructions.insertBefore(target, label);
            }
            dispatch.add(new JumpInsnNode(Opcodes.GOTO, label));
        }
        dispatch.add(invalid);
        dispatch.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        dispatch.add(new InsnNode(Opcodes.DUP));
        dispatch.add(new LdcInsnNode("Unknown player status continuation"));
        dispatch.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false));
        dispatch.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.insertBefore(method.instructions.getFirst(), dispatch);
        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i] instanceof FrameNode || region.contains(i) && !(instructions[i] instanceof LabelNode))
                method.instructions.remove(instructions[i]);
        }
        method.tryCatchBlocks.removeIf(block -> {
            for (AbstractInsnNode instruction = block.start; instruction != block.end; instruction = instruction.getNext())
                if (instruction.getOpcode() >= 0) return false;
            return true;
        });
        method.maxStack += 6;
        owner.methods.add(helper);
    }

    private static StatusExit statusExit(MethodNode method, StatusFlow flow, Frame<BasicValue>[] frames,
                                         int target, int id, boolean handler) {
        Frame<BasicValue> frame = frames[target];
        if (frame == null || frame.getStackSize() != (handler ? 1 : 0))
            throw new IllegalStateException("Nonempty player status continuation stack in " + method.name);
        List<Integer> locals = new ArrayList<>();
        List<Type> types = new ArrayList<>();
        for (int local = 0; local < frame.getLocals(); local++) {
            if (!flow.liveAt(target, local)) continue;
            Type type = frame.getLocal(local).getType();
            if (type == null) throw new IllegalStateException("Undefined player status continuation local " + local);
            locals.add(local);
            types.add(type);
            if (type.getSize() == 2) local++;
        }
        return new StatusExit(target, id, handler, locals, types, new LabelNode());
    }

    private record StatusExit(int target, int id, boolean handler, List<Integer> locals, List<Type> types, LabelNode bridge) {}

    private static final class StatusFlow extends Analyzer<BasicValue> {
        private final List<Set<Integer>> successors = new ArrayList<>();
        private final List<Set<Integer>> predecessors = new ArrayList<>();
        private final List<Set<Integer>> exceptional = new ArrayList<>();
        private final AbstractInsnNode[] instructions;

        private StatusFlow(ClassNode owner, MethodNode method) {
            super(new MixinVerifier(Opcodes.ASM9, Type.getObjectType(owner.name),
                    owner.superName == null ? null : Type.getObjectType(owner.superName),
                    owner.interfaces.stream().map(Type::getObjectType).toList(),
                    (owner.access & Opcodes.ACC_INTERFACE) != 0));
            instructions = method.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                successors.add(new HashSet<>());
                predecessors.add(new HashSet<>());
                exceptional.add(new HashSet<>());
            }
        }

        @Override protected void newControlFlowEdge(int instruction, int successor) {
            successors.get(instruction).add(successor);
            predecessors.get(successor).add(instruction);
        }

        @Override protected boolean newControlFlowExceptionEdge(int instruction, int successor) {
            exceptional.get(instruction).add(successor);
            return true;
        }

        private boolean liveAt(int target, int local) {
            Set<Integer> visited = new HashSet<>();
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            pending.add(target);
            while (!pending.isEmpty()) {
                int index = pending.removeFirst();
                if (!visited.add(index)) continue;
                AbstractInsnNode instruction = instructions[index];
                if (instruction instanceof VarInsnNode var && var.var == local) {
                    if (var.getOpcode() >= Opcodes.ILOAD && var.getOpcode() <= Opcodes.ALOAD) return true;
                    if (var.getOpcode() >= Opcodes.ISTORE && var.getOpcode() <= Opcodes.ASTORE) continue;
                }
                if (instruction instanceof IincInsnNode increment && increment.var == local) return true;
                pending.addAll(successors.get(index));
                pending.addAll(exceptional.get(index));
            }
            return false;
        }
    }

    private static void splitMove(ClassNode owner, MethodNode method, AbstractInsnNode start, AbstractInsnNode end,
                                  MethodNode guard) {
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
        String helperName = "tickweave$registration$move";
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
        String captures = "(L" + owner.name + ";" + method.desc.substring(1, method.desc.length() - 2) + ")" + SUPPLIER;
        dispatch.add(new InvokeDynamicInsnNode("get", captures, FACTORY, Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKESPECIAL, owner.name, helperName, helperDesc, false), Type.getMethodType("()" + RESULT)));
        dispatch.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.name, guard.name, guard.desc, false));
        int array = method.maxLocals++;
        dispatch.add(new VarInsnNode(Opcodes.ASTORE, array));
        LabelNode registered = new LabelNode();
        dispatch.add(new VarInsnNode(Opcodes.ALOAD, array));
        dispatch.add(new JumpInsnNode(Opcodes.IFNONNULL, registered));
        dispatch.add(new InsnNode(Opcodes.RETURN));
        dispatch.add(registered);
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
