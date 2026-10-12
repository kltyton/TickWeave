package com.axalotl.async.bootstrap.collections;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Preserves fixed membership when immutable configuration properties are copied into getter-only beans. */
final class ConfigurationSnapshots {
    private static final String CONF_KEY = "Lspace/arim/dazzleconf/annote/ConfKey;";
    private static final String LIST = "Ljava/util/List;";
    private static final String COPY = "(Ljava/util/Collection;)V";
    private static final String SNAPSHOT = "com/axalotl/async/common/collection/FixedListSnapshot";
    private static final Set<String> BACKINGS = Set.of("java/util/ArrayList", "it/unimi/dsi/fastutil/objects/ObjectArrayList");
    private static final System.Logger LOGGER = System.getLogger("TickWeave/Configuration");
    private final Map<String, List<Rule>> rules;

    private ConfigurationSnapshots(Map<String, List<Rule>> rules) {
        this.rules = Map.copyOf(rules);
    }

    static ConfigurationSnapshots scan(CollectionSources sources) throws IOException {
        Map<String, List<Rule>> selected = new HashMap<>();
        Set<String> parents = new HashSet<>();
        for (String name : sources.names()) {
            ClassNode header = sources.header(name);
            if (header != null) parents.add(header.superName);
        }
        for (String name : sources.names()) {
            ClassNode header = sources.header(name);
            if (header == null || (header.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0
                    || parents.contains(name) || header.fields.stream().noneMatch(ConfigurationSnapshots::privateFinalList)) continue;
            if (!fixedHierarchy(sources, header, new HashSet<>())) continue;
            ClassNode owner = null;
            for (MethodNode constructor : header.methods) {
                Type[] arguments = Type.getArgumentTypes(constructor.desc);
                if (!constructor.name.equals("<init>") || arguments.length != 1
                        || arguments[0].getSort() != Type.OBJECT) continue;
                String contract = arguments[0].getInternalName();
                if (!sources.subtype(name, contract)) continue;
                Map<String, String> properties = new HashMap<>();
                configurationProperties(sources, contract, properties, new HashSet<>());
                if (properties.isEmpty()) continue;
                if (owner == null) owner = sources.read(name);
                if (owner == null || !getterOnly(sources, owner, new HashSet<>())) break;
                MethodNode body = owner.methods.stream().filter(method -> method.name.equals("<init>")
                        && method.desc.equals(constructor.desc)).findFirst().orElseThrow();
                if (delegatedConstructor(owner, body.desc)) continue;
                for (Store store : stores(owner, body)) {
                    String getter = store.source.name + store.source.desc;
                    if (!properties.containsKey(getter) || !sources.subtype(contract, store.source.owner)
                            || !exposes(owner, store.field, store.source.name, store.source.desc)) continue;
                    selected.computeIfAbsent(name, ignored -> new ArrayList<>()).add(new Rule(body.desc,
                            store.field.name, store.source.owner, store.source.name, store.source.desc));
                }
            }
        }
        selected.replaceAll((name, value) -> List.copyOf(value));
        return new ConfigurationSnapshots(selected);
    }

    Set<String> targets() { return rules.keySet(); }

    int apply(ClassNode owner) {
        List<Rule> selected = rules.getOrDefault(owner.name, List.of());
        if (selected.isEmpty()) return 0;
        if (!fixedFields(owner) || !getterOnly(owner))
            throw new IllegalStateException("Configuration bean changed: " + owner.name);
        List<Store> matched = new ArrayList<>();
        for (Rule rule : selected) {
            MethodNode method = owner.methods.stream().filter(candidate -> candidate.name.equals("<init>")
                    && candidate.desc.equals(rule.constructor)).findFirst().orElseThrow(() ->
                            new IllegalStateException("Configuration copy constructor changed: " + owner.name));
            List<Store> copies = stores(owner, method).stream().filter(store -> store.field.name.equals(rule.field)
                    && store.source.owner.equals(rule.sourceOwner) && store.source.name.equals(rule.sourceName)
                    && store.source.desc.equals(rule.sourceDescriptor)).toList();
            if (delegatedConstructor(owner, rule.constructor) || copies.size() != 1
                    || !exposes(owner, copies.get(0).field, rule.sourceName, rule.sourceDescriptor))
                throw new IllegalStateException("Configuration list copy changed: " + owner.name + "." + rule.field);
            matched.add(copies.get(0));
        }
        for (Store store : matched) {
            store.method.instructions.insertBefore(store.field, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    SNAPSHOT, "copyOf", "(Ljava/util/Collection;)Ljava/util/List;", false));
            LOGGER.log(System.Logger.Level.INFO, "Fixed configuration-list membership: {0}.{1}", owner.name, store.field.name);
        }
        return matched.size();
    }

    private static boolean privateFinalList(FieldNode field) {
        return field.desc.equals(LIST) && (field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC))
                == (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL);
    }

    private static boolean fixedHierarchy(CollectionSources sources, ClassNode owner, Set<String> visited) throws IOException {
        if (owner.name.equals("java/lang/Object")) return true;
        if (!visited.add(owner.name) || !fixedFields(owner)) return false;
        if (owner.superName.equals("java/lang/Object")) return true;
        ClassNode parent = sources.header(owner.superName);
        return parent != null && fixedHierarchy(sources, parent, visited);
    }

    private static boolean getterOnly(CollectionSources sources, ClassNode owner, Set<String> visited) throws IOException {
        if (!visited.add(owner.name) || !getterOnly(owner)) return false;
        if (owner.superName.equals("java/lang/Object")) return true;
        ClassNode parent = sources.read(owner.superName);
        return parent != null && getterOnly(sources, parent, visited);
    }

    private static boolean fixedFields(ClassNode owner) {
        return owner.fields.stream().noneMatch(field -> (field.access & Opcodes.ACC_STATIC) == 0
                && (field.access & Opcodes.ACC_FINAL) == 0);
    }

    private static boolean getterOnly(ClassNode owner) {
        for (MethodNode method : owner.methods) {
            if (method.name.startsWith("<") || (method.access & Opcodes.ACC_STATIC) != 0) continue;
            List<AbstractInsnNode> code = CollectionPlan.boundedInstructions(method);
            if (code == null || code.size() != 3 || !(code.get(0) instanceof VarInsnNode receiver)
                    || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0
                    || !(code.get(1) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals(owner.name) || Type.getArgumentTypes(method.desc).length != 0
                    || !Type.getReturnType(method.desc).getDescriptor().equals(field.desc)
                    || code.get(2).getOpcode() != Type.getType(field.desc).getOpcode(Opcodes.IRETURN)) return false;
        }
        return true;
    }

    private static boolean delegatedConstructor(ClassNode owner, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && call.owner.equals(owner.name) && call.name.equals("<init>")
                        && call.desc.equals(descriptor)) return true;
            }
        }
        return false;
    }

    private static void configurationProperties(CollectionSources sources, String name, Map<String, String> properties,
                                                Set<String> visited) throws IOException {
        if (!visited.add(name)) return;
        ClassNode contract = sources.header(name);
        if (contract == null || (contract.access & Opcodes.ACC_INTERFACE) == 0) return;
        for (MethodNode method : contract.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0 && method.desc.equals("()" + LIST)
                    && (annotated(method.visibleAnnotations) || annotated(method.invisibleAnnotations)))
                properties.put(method.name + method.desc, name);
        }
        for (String parent : contract.interfaces) configurationProperties(sources, parent, properties, visited);
    }

    private static boolean annotated(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(annotation -> annotation.desc.equals(CONF_KEY));
    }

    private static boolean exposes(ClassNode owner, FieldInsnNode field, String name, String descriptor) {
        return owner.methods.stream().anyMatch(method -> {
            if (!method.name.equals(name) || !method.desc.equals(descriptor)) return false;
            List<AbstractInsnNode> code = CollectionPlan.boundedInstructions(method);
            return code != null && code.size() == 3 && code.get(1) instanceof FieldInsnNode read
                    && read.getOpcode() == Opcodes.GETFIELD && read.owner.equals(field.owner)
                    && read.name.equals(field.name) && read.desc.equals(field.desc);
        });
    }

    private static List<Store> stores(ClassNode owner, MethodNode method) {
        List<AbstractInsnNode> code = CollectionPlan.boundedInstructions(method);
        if (code == null) throw new IllegalStateException("Invalid configuration constructor instructions: " + owner.name);
        List<Store> result = new ArrayList<>();
        for (int i = 6; i < code.size(); i++) {
            // The owned copy is published as the constructor's last operation.
            if (i != code.size() - 2 || code.get(i + 1).getOpcode() != Opcodes.RETURN) continue;
            if (!(code.get(i) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !field.owner.equals(owner.name) || owner.fields.stream().noneMatch(declaration ->
                            declaration.name.equals(field.name) && privateFinalList(declaration))) continue;
            if (!(code.get(i - 1) instanceof MethodInsnNode copy) || copy.getOpcode() != Opcodes.INVOKESPECIAL
                    || !BACKINGS.contains(copy.owner) || !copy.name.equals("<init>") || !copy.desc.equals(COPY)
                    || !(code.get(i - 2) instanceof MethodInsnNode source) || source.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !source.desc.equals("()" + LIST) || !(code.get(i - 3) instanceof VarInsnNode input)
                    || input.getOpcode() != Opcodes.ALOAD || input.var != 1 || code.get(i - 4).getOpcode() != Opcodes.DUP
                    || !(code.get(i - 5) instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
                    || !allocation.desc.equals(copy.owner) || !(code.get(i - 6) instanceof VarInsnNode receiver)
                    || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0) continue;
            result.add(new Store(method, field, source));
        }
        return result;
    }

    private record Rule(String constructor, String field, String sourceOwner, String sourceName, String sourceDescriptor) {}
    private record Store(MethodNode method, FieldInsnNode field, MethodInsnNode source) {}
}
