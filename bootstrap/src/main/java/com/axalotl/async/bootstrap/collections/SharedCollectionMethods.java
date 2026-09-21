package com.axalotl.async.bootstrap.collections;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Gives singleton multimap operations, including their callers, one cooperative object resource. */
final class SharedCollectionMethods {
    private static final String TASKS = "com/axalotl/async/common/entity/task/SharedObjectTasks";
    private final Map<String, Set<String>> fields;

    private SharedCollectionMethods(Map<String, Set<String>> fields) { this.fields = Map.copyOf(fields); }

    static SharedCollectionMethods scan(CollectionSources sources, String entityBase) throws IOException {
        Map<String, Set<String>> selected = new HashMap<>();
        for (String name : sources.names()) {
            ClassNode header = sources.header(name);
            if (header == null || sources.subtype(name, entityBase)
                    || header.fields.stream().noneMatch(field -> (field.access & Opcodes.ACC_STATIC) != 0
                    && field.desc.equals("L" + name + ";"))) continue;
            Set<String> members = new HashSet<>();
            for (var field : header.fields) {
                if ((field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC))
                        == (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)
                        && field.desc.equals("Lcom/google/common/collect/Multimap;")) members.add(field.name + field.desc);
            }
            if (members.isEmpty()) continue;
            ClassNode node = sources.read(name);
            Set<String> methods = EntityCollectionMethods.selectMethods(node, members);
            if (!EntityCollectionMethods.receiverFields(node, members)
                    || node.methods.stream().anyMatch(method -> methods.contains(method.name + method.desc)
                    && (method.access & Opcodes.ACC_SYNCHRONIZED) != 0)) continue;
            selected.put(name, Set.copyOf(members));
        }
        return new SharedCollectionMethods(selected);
    }

    Set<String> targets() { return fields.keySet(); }

    int protect(ClassNode node) {
        Set<String> members = fields.get(node.name);
        if (members == null) return 0;
        if (!EntityCollectionMethods.receiverFields(node, members))
            throw new IllegalStateException("Shared multimap receiver changed before protection: " + node.name);
        Set<String> selected = EntityCollectionMethods.selectMethods(node, members);
        int count = 0;
        for (MethodNode method : node.methods) {
            if (!selected.contains(method.name + method.desc) || method.name.startsWith("<")
                    || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || EntityCollectionMethods.guarded(method, TASKS)) continue;
            if ((method.access & Opcodes.ACC_SYNCHRONIZED) != 0)
                throw new IllegalStateException("Shared multimap monitor changed before protection: " + node.name);
            EntityCollectionMethods.guard(node.name, method, TASKS);
            count++;
        }
        return count;
    }
}
