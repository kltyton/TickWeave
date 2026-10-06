package com.axalotl.async.bootstrap.collections;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Selects inventory callbacks whose synchronous call graph explicitly rejects off-thread access. */
final class InventoryThreadMethods {
    private static final String TASKS = "com/axalotl/async/common/item/InventoryItemTasks";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String FABRIC_ITEM = "net/minecraft/class_1792";
    private static final String DESCRIPTOR = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;IZ)V";
    private static final String FABRIC_DESCRIPTOR = "(Lnet/minecraft/class_1799;Lnet/minecraft/class_1937;Lnet/minecraft/class_1297;IZ)V";
    private final Map<String, Set<String>> methods;

    private InventoryThreadMethods(Map<String, Set<String>> methods) { this.methods = Map.copyOf(methods); }

    static InventoryThreadMethods scan(CollectionSources sources) throws IOException {
        Map<String, Set<String>> selected = new HashMap<>();
        Map<String, ClassNode> bodies = new HashMap<>();
        for (String name : sources.names()) {
            if (name.startsWith("net/minecraft/") || name.startsWith("com/axalotl/async/")) continue;
            if (!sources.subtype(name, ITEM) && !sources.subtype(name, FABRIC_ITEM)) continue;
            ClassNode node = read(sources, bodies, name);
            if (node == null) continue;
            for (MethodNode method : node.methods) {
                if (inventoryTick(method) && requiresMain(sources, bodies, node, method))
                    selected.computeIfAbsent(name, ignored -> new HashSet<>()).add(method.name + method.desc);
            }
        }
        selected.replaceAll((name, value) -> Set.copyOf(value));
        return new InventoryThreadMethods(selected);
    }

    Set<String> targets() { return methods.keySet(); }

    int protect(ClassNode node) {
        int count = 0;
        for (MethodNode method : node.methods) {
            if (methods.getOrDefault(node.name, Set.of()).contains(method.name + method.desc)
                    && !EntityCollectionMethods.guarded(method, TASKS)) {
                EntityCollectionMethods.guard(node.name, method, TASKS);
                count++;
            }
        }
        return count;
    }

    private static boolean inventoryTick(MethodNode method) {
        return (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) == 0
                && ((Set.of("inventoryTick", "m_6883_").contains(method.name) && method.desc.equals(DESCRIPTOR))
                || (method.name.equals("method_7888") && method.desc.equals(FABRIC_DESCRIPTOR)));
    }

    private static boolean requiresMain(CollectionSources sources, Map<String, ClassNode> bodies,
                                        ClassNode owner, MethodNode entry) throws IOException {
        var pending = new ArrayDeque<Site>();
        var visited = new HashSet<Site>();
        pending.add(new Site(owner.name, entry.name, entry.desc));
        while (!pending.isEmpty()) {
            Site site = pending.removeFirst();
            if (!visited.add(site) || site.owner.startsWith("net/minecraft/")
                    || site.owner.startsWith("java/") || site.name.startsWith("<")) continue;
            ClassNode node = read(sources, bodies, site.owner);
            if (node == null) continue;
            MethodNode method = node.methods.stream().filter(m -> m.name.equals(site.name) && m.desc.equals(site.descriptor))
                    .findFirst().orElse(null);
            if (method == null || method.tryCatchBlocks.stream().anyMatch(t -> Set.of(
                    "java/lang/IllegalStateException", "java/lang/RuntimeException", "java/lang/Exception",
                    "java/lang/Throwable").contains(t.type == null ? "java/lang/Throwable" : t.type))) continue;
            List<AbstractInsnNode> code = CollectionPlan.boundedInstructions(method);
            if (code == null) continue;
            if (threadGuard(code)) return true;
            for (var instruction : code) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                ClassNode target = read(sources, bodies, call.owner);
                if (target == null) continue;
                MethodNode callee = target.methods.stream().filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc))
                        .findFirst().orElse(null);
                if (callee != null && (call.getOpcode() == Opcodes.INVOKESTATIC || call.getOpcode() == Opcodes.INVOKESPECIAL
                        || (target.access & Opcodes.ACC_FINAL) != 0
                        || (callee.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0))
                    pending.add(new Site(call.owner, call.name, call.desc));
            }
        }
        return false;
    }

    private static boolean threadGuard(List<AbstractInsnNode> code) {
        for (int i = 0; i + 6 < code.size(); i++) {
            if (!(code.get(i) instanceof MethodInsnNode call) || !call.desc.equals("()Z")
                    || !Set.of("net/minecraft/server/MinecraftServer", "net/minecraft/class_3176").contains(call.owner)
                    || !Set.of("isSameThread", "m_18695_", "method_18854").contains(call.name)) continue;
            if (code.get(i + 1) instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE
                    && code.get(i + 2) instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && allocation.desc.equals("java/lang/IllegalStateException")
                    && code.get(i + 3).getOpcode() == Opcodes.DUP
                    && code.get(i + 4) instanceof LdcInsnNode message && message.cst instanceof String
                    && code.get(i + 5) instanceof MethodInsnNode constructor && constructor.getOpcode() == Opcodes.INVOKESPECIAL
                    && constructor.owner.equals(allocation.desc) && constructor.name.equals("<init>")
                    && constructor.desc.equals("(Ljava/lang/String;)V") && code.get(i + 6).getOpcode() == Opcodes.ATHROW) return true;
        }
        return false;
    }

    private static ClassNode read(CollectionSources sources, Map<String, ClassNode> bodies, String name) throws IOException {
        if (!bodies.containsKey(name)) bodies.put(name, sources.read(name));
        return bodies.get(name);
    }

    private record Site(String owner, String name, String descriptor) {}
}
