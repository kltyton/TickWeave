package com.axalotl.async.common.bootstrap;

import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/** Protects constructor-only appends to shared resource-keyed registries after other Mixins apply. */
public final class SharedRegistryConstructorExtension implements IExtension {
    private static final String HASH_MAP = "Ljava/util/HashMap;";
    private static final String MARKER = "tickweave$registryConstructorGuard";

    @Override public boolean checkActive(MixinEnvironment environment) { return true; }
    @Override public void preApply(ITargetClassContext context) {}
    @Override public void export(MixinEnvironment environment, String name, boolean force, ClassNode node) {}
    @Override public void postApply(ITargetClassContext context) { apply(context.getClassNode()); }

    public static int apply(ClassNode node) {
        if (node.fields.stream().anyMatch(field -> field.name.equals(MARKER))) return 0;
        int protectedCount = 0;
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0 || !field.desc.equals(HASH_MAP)) continue;
            for (String key : List.of("net/minecraft/resources/ResourceLocation", "net/minecraft/class_2960")) {
                String signature = "Ljava/util/HashMap<L" + key + ";Ljava/util/List<L" + node.name + ";>;>;";
                if (!signature.equals(field.signature)) continue;
                boolean reassigned = node.methods.stream().filter(method -> !method.name.equals("<clinit>"))
                        .anyMatch(method -> writes(method, node.name, field.name));
                if (reassigned) continue;
                for (MethodNode method : node.methods) {
                    if (!method.name.equals("<init>") || !method.desc.equals("(L" + key + ";)V")
                            || !method.tryCatchBlocks.isEmpty()) continue;
                    AbstractInsnNode initialized = null, returned = null;
                    int returns = 0, reads = 0;
                    boolean append = false;
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (instruction instanceof MethodInsnNode call) {
                            if (call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(node.superName)
                                    && call.name.equals("<init>")) initialized = instruction;
                            if (call.owner.equals("java/util/List") && call.name.equals("add")
                                    && call.desc.equals("(Ljava/lang/Object;)Z")) append = true;
                        }
                        if (instruction instanceof FieldInsnNode access && access.getOpcode() == Opcodes.GETSTATIC
                                && access.owner.equals(node.name) && access.name.equals(field.name)) reads++;
                        if (instruction.getOpcode() == Opcodes.RETURN) { returned = instruction; returns++; }
                    }
                    if (initialized == null || returns != 1 || reads < 2 || !append
                            || !closedBody(node, method, initialized, field.name)) continue;
                    guard(node.name, field.name, method, initialized, returned);
                    protectedCount++;
                }
            }
        }
        if (protectedCount != 0) node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, MARKER, "Z", null, 1));
        return protectedCount;
    }

    private static boolean writes(MethodNode method, String owner, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.owner.equals(owner) && field.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean closedBody(ClassNode node, MethodNode method, AbstractInsnNode initialized, String field) {
        for (AbstractInsnNode instruction = initialized.getNext(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode access
                    && !(access.getOpcode() == Opcodes.GETSTATIC && access.owner.equals(node.name)
                    && access.name.equals(field))) return false;
            if (!(instruction instanceof MethodInsnNode call)) continue;
            boolean nativeOperation = call.owner.equals("java/util/HashMap")
                    && List.of("containsKey", "get", "put").contains(call.name)
                    || call.owner.equals("java/util/List") && call.name.equals("add")
                    || call.owner.equals("java/util/ArrayList") && call.name.equals("<init>") && call.desc.equals("()V");
            boolean appliedGet = call.owner.equals(node.name)
                    && call.desc.equals("(Ljava/util/HashMap;Ljava/lang/Object;)Ljava/lang/Object;")
                    && node.methods.stream().anyMatch(helper -> helper.name.equals(call.name) && helper.desc.equals(call.desc)
                    && (helper.access & Opcodes.ACC_PRIVATE) != 0);
            if (!nativeOperation && !appliedGet) return false;
        }
        return true;
    }

    private static void guard(String owner, String field, MethodNode method, AbstractInsnNode initialized,
                              AbstractInsnNode returned) {
        int lock = method.maxLocals++, failure = method.maxLocals++;
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        InsnList enter = new InsnList();
        enter.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, field, HASH_MAP));
        enter.add(new InsnNode(Opcodes.DUP));
        enter.add(new VarInsnNode(Opcodes.ASTORE, lock));
        enter.add(new InsnNode(Opcodes.MONITORENTER));
        enter.add(start);
        method.instructions.insert(initialized, enter);
        InsnList leave = new InsnList();
        leave.add(end);
        leave.add(new VarInsnNode(Opcodes.ALOAD, lock));
        leave.add(new InsnNode(Opcodes.MONITOREXIT));
        method.instructions.insertBefore(returned, leave);
        method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, failure));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, lock));
        method.instructions.add(new InsnNode(Opcodes.MONITOREXIT));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, failure));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        for (AbstractInsnNode instruction : method.instructions.toArray())
            if (instruction instanceof FrameNode) method.instructions.remove(instruction);
        method.maxStack += 2;
    }
}
