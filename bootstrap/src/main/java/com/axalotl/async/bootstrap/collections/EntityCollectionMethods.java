package com.axalotl.async.bootstrap.collections;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/** Protects compound operations on an entity's private collection of other entities. */
final class EntityCollectionMethods {
    private static final String TASKS = "com/axalotl/async/common/entity/task/EntityTasks";
    private static final Pattern MEMBERS = Pattern.compile("^Ljava/util/(?:List|Set|Collection)<L([^;<]+);>;$");
    private static final Handle LAMBDA = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
    private final Map<String, Set<String>> fields;

    private EntityCollectionMethods(Map<String, Set<String>> fields) { this.fields = Map.copyOf(fields); }

    static EntityCollectionMethods scan(CollectionSources sources, String entityBase) throws IOException {
        Map<String, Set<String>> selected = new HashMap<>();
        for (String name : sources.names()) {
            if (name.startsWith("net/minecraft/") || name.startsWith("com/axalotl/async/")) continue;
            ClassNode node = sources.header(name);
            if (node == null || !sources.subtype(name, entityBase)) continue;
            for (FieldNode field : node.fields) {
                if ((field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC))
                        != (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)
                        || field.signature == null) continue;
                var matcher = MEMBERS.matcher(field.signature);
                if (matcher.matches() && sources.subtype(matcher.group(1), entityBase))
                    selected.computeIfAbsent(name, ignored -> new HashSet<>()).add(field.name + field.desc);
            }
        }
        for (String name : Set.copyOf(selected.keySet())) {
            ClassNode node = sources.read(name);
            Set<String> methods = selectMethods(node, selected.get(name));
            boolean applicable = node.methods.stream().anyMatch(method -> !method.name.startsWith("<")
                    && methods.contains(method.name + method.desc));
            boolean monitor = node.methods.stream().anyMatch(method -> methods.contains(method.name + method.desc)
                    && (method.access & Opcodes.ACC_SYNCHRONIZED) != 0);
            if (!applicable || monitor || !receiverFields(node, selected.get(name))) selected.remove(name);
        }
        selected.replaceAll((name, value) -> Set.copyOf(value));
        return new EntityCollectionMethods(selected);
    }

    Set<String> targets() { return fields.keySet(); }

    int protect(ClassNode node) {
        Set<String> members = fields.get(node.name);
        if (members == null) return 0;
        if (!receiverFields(node, members))
            throw new IllegalStateException("Entity collection receiver changed before protection: " + node.name);
        Set<String> selected = selectMethods(node, members);
        for (MethodNode method : node.methods) {
            if (selected.contains(method.name + method.desc) && (method.access & Opcodes.ACC_SYNCHRONIZED) != 0)
                throw new IllegalStateException("Entity collection monitor changed before protection: " + node.name);
        }
        int count = 0;
        for (MethodNode method : node.methods) {
            if (!selected.contains(method.name + method.desc) || method.name.startsWith("<")
                    || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || guarded(method, TASKS)) continue;
            protectTraversals(node.name, method, members);
            guard(node.name, method, TASKS);
            count++;
        }
        return count;
    }

    static Set<String> selectMethods(ClassNode node, Set<String> members) {
        Set<String> selected = new HashSet<>();
        Map<String, Set<String>> calls = new HashMap<>();
        for (MethodNode method : node.methods) {
            List<AbstractInsnNode> code = CollectionPlan.boundedInstructions(method);
            if (code == null) throw new IllegalStateException("Inconsistent entity method instructions: " + node.name + "." + method.name);
            String key = method.name + method.desc;
            for (AbstractInsnNode instruction : code) {
                if (instruction instanceof FieldInsnNode field && field.owner.equals(node.name)
                        && members.contains(field.name + field.desc)) selected.add(key);
                if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name))
                    calls.computeIfAbsent(key, ignored -> new HashSet<>()).add(call.name + call.desc);
            }
        }
        boolean changed;
        do {
            changed = false;
            for (var entry : calls.entrySet()) {
                if (!selected.contains(entry.getKey()) && entry.getValue().stream().anyMatch(selected::contains))
                    changed |= selected.add(entry.getKey());
            }
        } while (changed);
        return selected;
    }

    static boolean receiverFields(ClassNode node, Set<String> members) {
        for (MethodNode method : node.methods) {
            List<AbstractInsnNode> code = CollectionPlan.boundedInstructions(method);
            if (code == null) return false;
            for (int i = 0; i < code.size(); i++) {
                if (!(code.get(i) instanceof FieldInsnNode field) || !field.owner.equals(node.name)
                        || !members.contains(field.name + field.desc)) continue;
                if ((method.access & Opcodes.ACC_STATIC) != 0) return false;
                if (field.getOpcode() == Opcodes.GETFIELD && (i == 0
                        || !(code.get(i - 1) instanceof VarInsnNode receiver)
                        || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0)) return false;
                // A returned collection escapes the method resource and needs a different ownership boundary.
                if (field.getOpcode() == Opcodes.GETFIELD && i + 1 < code.size()
                        && code.get(i + 1).getOpcode() == Opcodes.ARETURN) return false;
            }
        }
        return true;
    }

    static boolean guarded(MethodNode method, String tasks) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext())
            if (instruction instanceof MethodInsnNode call && call.owner.equals(tasks)
                    && call.name.equals("requiresOwnership")) return true;
        return false;
    }

    private static void protectTraversals(String owner, MethodNode method, Set<String> members) {
        org.objectweb.asm.tree.analysis.Frame<SourceValue>[] frames;
        try { frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method); }
        catch (AnalyzerException failure) {
            throw new IllegalStateException("Cannot inspect entity collection traversal: " + owner + "." + method.name, failure);
        }
        var sites = new IdentityHashMap<MethodInsnNode, Boolean>();
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            var frame = frames[index++];
            if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("forEach")
                    || !call.desc.equals("(Ljava/util/function/Consumer;)V")
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE || frame == null || frame.getStackSize() < 2) continue;
            SourceValue receiver = frame.getStack(frame.getStackSize() - 2);
            if (!receiver.insns.isEmpty() && receiver.insns.stream().allMatch(origin ->
                    origin instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                            && field.owner.equals(owner) && members.contains(field.name + field.desc))) sites.put(call, Boolean.TRUE);
        }
        for (MethodInsnNode call : sites.keySet()) {
            method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, TASKS, "forEachOwned",
                    "(Ljava/util/Collection;Ljava/util/function/Consumer;Ljava/lang/Object;)V", false));
        }
        if (!sites.isEmpty()) method.maxStack++;
    }

    static void guard(String owner, MethodNode method, String tasks) {
        Type result = Type.getReturnType(method.desc);
        boolean returnsVoid = result.getSort() == Type.VOID;
        Type callback = Type.getObjectType(returnsVoid ? "java/lang/Runnable" : "java/util/function/Supplier");
        String callbackName = returnsVoid ? "run" : "get";
        Type erasedResult = returnsVoid ? Type.VOID_TYPE : Type.getType(Object.class);
        Type[] arguments = Type.getArgumentTypes(method.desc);
        Type[] captured = new Type[arguments.length + 1];
        captured[0] = Type.getObjectType(owner);
        System.arraycopy(arguments, 0, captured, 1, arguments.length);
        InsnList code = new InsnList();
        LabelNode direct = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, tasks, "requiresOwnership", "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, direct));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int local = 1;
        for (Type argument : arguments) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        // Invoke the exact body, including for super calls; its owned fast path prevents recursion.
        code.add(new InvokeDynamicInsnNode(callbackName, Type.getMethodDescriptor(callback, captured), LAMBDA,
                Type.getMethodType(Type.getMethodDescriptor(erasedResult)),
                new Handle(Opcodes.H_INVOKESPECIAL, owner, method.name, method.desc, false),
                Type.getMethodType(Type.getMethodDescriptor(boxed(result)))));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, tasks, returnsVoid ? "executeOwned" : "callOwned",
                "(Ljava/lang/Object;" + callback.getDescriptor() + ")" + erasedResult.getDescriptor(), false));
        if (!returnsVoid) {
            Type box = boxed(result);
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, box.getInternalName()));
            if (result.getSort() != Type.OBJECT && result.getSort() != Type.ARRAY)
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, box.getInternalName(), result.getClassName() + "Value",
                        "()" + result.getDescriptor(), false));
        }
        code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        code.add(direct);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, local + 1);
    }

    private static Type boxed(Type type) {
        String name = switch (type.getSort()) {
            case Type.BOOLEAN -> "Boolean";
            case Type.BYTE -> "Byte";
            case Type.CHAR -> "Character";
            case Type.SHORT -> "Short";
            case Type.INT -> "Integer";
            case Type.FLOAT -> "Float";
            case Type.LONG -> "Long";
            case Type.DOUBLE -> "Double";
            default -> null;
        };
        return name == null ? type : Type.getObjectType("java/lang/" + name);
    }
}
