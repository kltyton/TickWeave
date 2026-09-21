package com.axalotl.async.bootstrap.collections;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/** Selects closed entity cache/queue access shapes and preserves their native collection operations. */
public final class CollectionPlan {
    private static final System.Logger LOGGER = System.getLogger("TickWeave/Collections");
    private static final String MAP = "Ljava/util/Map;";
    private static final String LIST = "Ljava/util/List;";
    private static final String SET = "Ljava/util/Set;";
    private static final Pattern MAP_TYPES = Pattern.compile("^Ljava/util/Map<L([^;<]+);L([^;<]+);>;$");
    private static final Pattern LIST_TYPE = Pattern.compile("^Ljava/util/List<L([^;<]+);>;$");
    private static final Pattern SET_TYPE = Pattern.compile("^Ljava/util/Set<L([^;<]+);>;$");
    private static final String COMPUTE = "computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;";
    private static final Set<String> CACHE_READS = Set.of("get(Ljava/lang/Object;)Ljava/lang/Object;",
            "containsKey(Ljava/lang/Object;)Z", "remove(Ljava/lang/Object;)Ljava/lang/Object;", "clear()V", "isEmpty()Z", "size()I");
    private static final Set<String> SET_OPERATIONS = Set.of("add(Ljava/lang/Object;)Z", "remove(Ljava/lang/Object;)Z",
            "contains(Ljava/lang/Object;)Z", "clear()V", "isEmpty()Z", "size()I");
    private static final Set<String> QUEUE_OPERATIONS = Set.of("add(Ljava/lang/Object;)Z", "clear()V",
            "removeIf(Ljava/util/function/Predicate;)Z", "isEmpty()Z", "size()I");
    private static final Set<String> WEAK_OPERATIONS = Set.of("get(Ljava/lang/Object;)Ljava/lang/Object;",
            "containsKey(Ljava/lang/Object;)Z", "put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            "remove(Ljava/lang/Object;)Ljava/lang/Object;", "clear()V", "isEmpty()Z", "size()I");
    private final Map<String, List<Rule>> rules;
    private final EntityCollectionMethods entityMethods;
    private final SharedCollectionMethods sharedMethods;

    private CollectionPlan(Map<String, List<Rule>> rules, EntityCollectionMethods entityMethods, SharedCollectionMethods sharedMethods) {
        this.rules = Map.copyOf(rules);
        this.entityMethods = entityMethods;
        this.sharedMethods = sharedMethods;
    }

    public static CollectionPlan scan(List<Path> roots, String entityBase, String attributeBase) throws IOException {
        return new Scan(new CollectionSources(roots), entityBase, attributeBase).run();
    }

    public Set<String> targets() {
        Set<String> targets = new HashSet<>(rules.keySet());
        targets.addAll(entityMethods.targets());
        targets.addAll(sharedMethods.targets());
        return Set.copyOf(targets);
    }

    public boolean isScalarCollectionOnly(String target) {
        List<Rule> selected = rules.get(target);
        return selected != null && !selected.isEmpty()
                && selected.stream().allMatch(rule -> rule.kind == Kind.SCALAR_MAP)
                && !entityMethods.targets().contains(target) && !sharedMethods.targets().contains(target);
    }

    /** Retains the original HashMap/WeakHashMap/ArrayList, including null and callback behavior. */
    public int protect(ClassNode target) {
        int wrapped = entityMethods.protect(target) + sharedMethods.protect(target);
        for (Rule rule : rules.getOrDefault(target.name, List.of())) {
            List<Store> stores = new ArrayList<>();
            boolean matched = true;
            for (MethodNode method : target.methods) {
                List<AbstractInsnNode> code = boundedInstructions(method);
                if (code == null) { matched = false; break; }
                for (int i = 0; i < code.size(); i++) {
                    if (!(code.get(i) instanceof FieldInsnNode field) || !rule.field.matches(field)
                            || !(field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC)) continue;
                    if (!emptyAllocation(code, i, rule.backing)) { matched = false; break; }
                    stores.add(new Store(method, field));
                }
                if (!matched) break;
            }
            if (!matched || stores.size() != rule.assignments) {
                LOGGER.log(System.Logger.Level.WARNING, "Shared collection initializer changed before protection: {0}", rule.field);
                continue;
            }
            for (Store store : stores) {
                boolean list = rule.field.descriptor.equals(LIST);
                boolean set = rule.field.descriptor.equals(SET);
                store.method.instructions.insertBefore(store.field, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/util/Collections", list ? "synchronizedList" : set ? "synchronizedSet" : "synchronizedMap",
                        "(" + rule.field.descriptor + ")" + rule.field.descriptor, false));
            }
            for (FieldNode field : target.fields) {
                if (field.name.equals(rule.field.name) && field.desc.equals(rule.field.descriptor)
                        && (field.access & Opcodes.ACC_FINAL) == 0) field.access |= Opcodes.ACC_VOLATILE;
            }
            wrapped += stores.size();
            LOGGER.log(System.Logger.Level.INFO, "Protected shared collection operations: {0}", rule.field);
        }
        return wrapped;
    }

    private static boolean emptyAllocation(List<AbstractInsnNode> code, int store, String backing) {
        return store >= 3 && code.get(store - 3) instanceof TypeInsnNode allocation
                && allocation.getOpcode() == Opcodes.NEW && allocation.desc.equals(backing)
                && code.get(store - 2).getOpcode() == Opcodes.DUP
                && code.get(store - 1) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                && call.owner.equals(backing) && call.name.equals("<init>") && call.desc.equals("()V");
    }

    static List<AbstractInsnNode> boundedInstructions(MethodNode method) {
        var instructions = method.instructions;
        List<AbstractInsnNode> code = new ArrayList<>();
        AbstractInsnNode previous = null;
        AbstractInsnNode current = instructions.getFirst();
        for (int i = 0; i < instructions.size(); i++) {
            if (current == null || current.getPrevious() != previous) return null;
            if (current.getOpcode() >= 0) code.add(current);
            previous = current;
            current = current.getNext();
        }
        return current == null && previous == instructions.getLast() ? code : null;
    }

    private record FieldId(String owner, String name, String descriptor) {
        boolean matches(FieldInsnNode field) {
            return owner.equals(field.owner) && name.equals(field.name) && descriptor.equals(field.desc);
        }
        FieldInsnNode origin() { return new FieldInsnNode(Opcodes.GETSTATIC, owner, name, descriptor); }
        @Override public String toString() { return owner.replace('/', '.') + "." + name; }
    }
    private record MethodId(String owner, String name, String descriptor) {}
    private record Rule(FieldId field, String backing, int assignments, Kind kind) {}
    private record Store(MethodNode method, FieldInsnNode field) {}
    private enum Kind { CACHE, QUEUE, WEAK_ENTITY, KEY_SET, SCALAR_MAP }

    private static final class Candidate {
        final FieldId field;
        final Kind kind;
        final String key;
        final String backing;
        final Set<Handle> factories = new HashSet<>();
        int assignments;
        boolean operation;
        boolean removal;
        boolean rejected;
        Candidate(FieldId field, Kind kind, String key) {
            this.field = field;
            this.kind = kind;
            this.key = key;
            this.backing = kind == Kind.QUEUE ? "java/util/ArrayList"
                    : kind == Kind.WEAK_ENTITY ? "java/util/WeakHashMap"
                    : kind == Kind.KEY_SET ? "java/util/HashSet" : "java/util/HashMap";
        }
    }

    private static final class Scan {
        final CollectionSources sources;
        final String entityBase;
        final String attributeBase;
        final Map<FieldId, Candidate> candidates = new LinkedHashMap<>();
        final Map<FieldId, FieldId> canonical = new HashMap<>();
        final Map<MethodId, Set<FieldId>> getters = new HashMap<>();
        final Map<MethodId, Set<FieldId>> calls = new HashMap<>();
        final Set<String> staticReferences = new HashSet<>();

        Scan(CollectionSources sources, String entityBase, String attributeBase) {
            this.sources = sources;
            this.entityBase = entityBase;
            this.attributeBase = attributeBase;
        }

        CollectionPlan run() throws IOException {
            for (String name : sources.names()) {
                ClassNode node = sources.header(name);
                if (node == null) continue;
                for (FieldNode field : node.fields) {
                    if ((field.access & Opcodes.ACC_STATIC) != 0 && field.desc.startsWith("L"))
                        staticReferences.add(Type.getType(field.desc).getInternalName());
                }
            }
            for (String name : sources.names()) {
                ClassNode node = sources.header(name);
                if (node == null || name.startsWith("com/axalotl/async/") || !mixinTargets(node).isEmpty()) continue;
                for (FieldNode field : node.fields) select(node, field);
            }
            for (Path path : sources.paths()) {
                ClassNode node = sources.read(path);
                if (node != null && !sources.ambiguous(node.name)) {
                    rejectHiddenMixinAccess(node);
                    discoverGetters(node);
                }
            }
            for (Path path : sources.paths()) {
                ClassNode node = sources.read(path);
                if (node != null) inspect(node);
            }
            CacheConstructor constructors = new CacheConstructor(sources);
            Map<String, List<Rule>> result = new TreeMap<>();
            for (Candidate candidate : candidates.values()) {
                if (candidate.rejected || candidate.assignments == 0 || !candidate.operation) continue;
                if (candidate.kind == Kind.QUEUE && !candidate.removal) continue;
                if (candidate.kind == Kind.CACHE || candidate.kind == Kind.SCALAR_MAP) {
                    if (candidate.kind == Kind.CACHE && candidate.factories.isEmpty()) continue;
                    for (Handle factory : candidate.factories) {
                        if (!constructors.accepts(factory, candidate.field.owner, candidate.key)) candidate.rejected = true;
                    }
                    if (candidate.rejected) continue;
                }
                result.computeIfAbsent(candidate.field.owner, ignored -> new ArrayList<>())
                        .add(new Rule(candidate.field, candidate.backing, candidate.assignments, candidate.kind));
            }
            result.replaceAll((owner, selected) -> List.copyOf(selected));
            return new CollectionPlan(result, EntityCollectionMethods.scan(sources, entityBase),
                    SharedCollectionMethods.scan(sources, entityBase));
        }

        private void select(ClassNode owner, FieldNode field) throws IOException {
            if (field.signature == null) return;
            FieldId id = new FieldId(owner.name, field.name, field.desc);
            if (field.desc.equals(MAP)) {
                var types = MAP_TYPES.matcher(field.signature);
                if (!types.matches()) return;
                String key = types.group(1);
                String value = types.group(2);
                boolean shared = (field.access & Opcodes.ACC_STATIC) != 0 || globallyReachable(owner.name);
                if (!shared) return;
                if (scalar(key) && scalar(value)) {
                    candidates.put(id, new Candidate(id, Kind.SCALAR_MAP, key));
                } else if ((field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0 && (key.equals("java/util/UUID")
                        || key.equals("java/lang/String") && (sources.subtype(value, attributeBase)
                        || value.equals("java/util/UUID") || primitiveState(value)))) {
                    candidates.put(id, new Candidate(id, Kind.CACHE, key));
                } else if ((field.access & Opcodes.ACC_FINAL) != 0 && sources.subtype(key, entityBase)) {
                    candidates.put(id, new Candidate(id, Kind.WEAK_ENTITY, key));
                }
            } else if (field.desc.equals(SET)
                    && (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) {
                var type = SET_TYPE.matcher(field.signature);
                if (type.matches() && (type.group(1).equals("java/lang/String") || type.group(1).equals("java/util/UUID")))
                    candidates.put(id, new Candidate(id, Kind.KEY_SET, type.group(1)));
            } else if (field.desc.equals(LIST)
                    && (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) {
                var type = LIST_TYPE.matcher(field.signature);
                if (type.matches() && sources.subtype(type.group(1), entityBase))
                    candidates.put(id, new Candidate(id, Kind.QUEUE, type.group(1)));
            }
        }

        private static boolean scalar(String name) {
            return switch (name) {
                case "java/lang/String", "java/util/UUID", "java/lang/Boolean", "java/lang/Byte",
                        "java/lang/Short", "java/lang/Character", "java/lang/Integer", "java/lang/Long",
                        "java/lang/Float", "java/lang/Double" -> true;
                default -> false;
            };
        }

        private boolean primitiveState(String name) throws IOException {
            ClassNode type = sources.header(name);
            return type != null && "java/lang/Object".equals(type.superName) && type.interfaces.isEmpty()
                    && type.fields.stream().allMatch(field -> (field.access & Opcodes.ACC_STATIC) == 0
                    && Type.getType(field.desc).getSort() >= Type.BOOLEAN && Type.getType(field.desc).getSort() <= Type.DOUBLE);
        }

        private boolean globallyReachable(String owner) throws IOException {
            Set<String> visited = new HashSet<>();
            while (owner != null && visited.add(owner)) {
                if (staticReferences.contains(owner)) return true;
                ClassNode node = sources.header(owner);
                owner = node == null ? null : node.superName;
            }
            return false;
        }

        private FieldId field(FieldInsnNode instruction) throws IOException {
            return field(new FieldId(instruction.owner, instruction.name, instruction.desc), new HashSet<>());
        }

        private FieldId field(FieldId id, Set<String> visited) throws IOException {
            if (canonical.containsKey(id)) return canonical.get(id);
            if (!visited.add(id.owner)) return null;
            ClassNode owner = sources.header(id.owner);
            if (owner == null) return null;
            for (FieldNode declaration : owner.fields) {
                if (!declaration.name.equals(id.name) || !declaration.desc.equals(id.descriptor)) continue;
                String target = hasAnnotation(declaration.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;")
                        || hasAnnotation(declaration.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;")
                        ? mixinTarget(owner) : null;
                FieldId resolved = target == null ? id : field(new FieldId(target, id.name, id.descriptor), visited);
                canonical.put(id, resolved);
                return resolved;
            }
            for (String parent : owner.interfaces) {
                FieldId resolved = field(new FieldId(parent, id.name, id.descriptor), visited);
                if (resolved != null) { canonical.put(id, resolved); return resolved; }
            }
            FieldId resolved = owner.superName == null ? null
                    : field(new FieldId(owner.superName, id.name, id.descriptor), visited);
            canonical.put(id, resolved);
            return resolved;
        }

        private void discoverGetters(ClassNode owner) throws IOException {
            for (MethodNode method : owner.methods) {
                String returned = Type.getReturnType(method.desc).getDescriptor();
                if (!(returned.equals(MAP) || returned.equals(LIST)) || method.instructions.size() == 0) continue;
                Frame<SourceValue>[] frames = analyze(owner, method, new Origins(Map.of()));
                if (frames == null) continue;
                Set<FieldId> returnedFields = new HashSet<>();
                boolean closed = true;
                int index = 0;
                for (AbstractInsnNode instruction : method.instructions) {
                    Frame<SourceValue> frame = frames[index++];
                    if (instruction.getOpcode() != Opcodes.ARETURN || frame == null) continue;
                    SourceValue value = frame.getStack(frame.getStackSize() - 1);
                    if (value.insns.isEmpty()) closed = false;
                    for (AbstractInsnNode origin : value.insns) {
                        if (origin instanceof FieldInsnNode read) {
                            FieldId id = field(read);
                            if (id != null && candidates.containsKey(id)) returnedFields.add(id);
                            else closed = false;
                        } else if (origin.getOpcode() != Opcodes.ACONST_NULL) closed = false;
                    }
                }
                if (closed && !returnedFields.isEmpty())
                    getters.put(new MethodId(owner.name, method.name, method.desc), Set.copyOf(returnedFields));
            }
        }

        private void rejectHiddenMixinAccess(ClassNode owner) throws IOException {
            Set<String> targets = mixinTargets(owner);
            if (targets.isEmpty()) return;
            boolean hidden = false;
            for (FieldNode field : owner.fields) {
                if (!(field.desc.equals(MAP) || field.desc.equals(LIST) || field.desc.equals(SET))
                        || !(hasAnnotation(field.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;")
                        || hasAnnotation(field.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;"))) continue;
                if (targets.size() != 1 || field(new FieldInsnNode(Opcodes.GETSTATIC, owner.name, field.name, field.desc)) == null)
                    hidden = true;
            }
            for (MethodNode method : owner.methods) {
                List<AnnotationNode> annotations = new ArrayList<>();
                if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
                if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
                boolean changesMethod = annotations.stream().anyMatch(annotation ->
                        annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/")
                                || annotation.desc.startsWith("Lcom/llamalad7/mixinextras/injector/"));
                if (!changesMethod) continue;
                List<Type> types = new ArrayList<>(List.of(Type.getArgumentTypes(method.desc)));
                types.add(Type.getReturnType(method.desc));
                for (Type type : types) {
                    if (type.getSort() != Type.OBJECT) continue;
                    String name = type.getInternalName();
                    if (name.equals("java/lang/Object") || name.equals("java/util/Map") || name.equals("java/util/List")
                            || name.equals("java/util/Collection") || name.equals("java/util/HashMap")
                            || name.equals("java/util/WeakHashMap") || name.equals("java/util/ArrayList")
                            || name.equals("java/util/Set") || name.equals("java/util/HashSet")) hidden = true;
                }
            }
            if (hidden) {
                for (Candidate candidate : candidates.values()) {
                    for (String target : targets) {
                        if (sources.subtype(target, candidate.field.owner)) candidate.rejected = true;
                    }
                }
            }
        }

        private Set<FieldId> getter(MethodInsnNode call) throws IOException {
            MethodId id = new MethodId(call.owner, call.name, call.desc);
            if (calls.containsKey(id)) return calls.get(id);
            Set<FieldId> fields = new HashSet<>();
            for (var entry : getters.entrySet()) {
                MethodId method = entry.getKey();
                if (!method.name.equals(call.name) || !method.descriptor.equals(call.desc)) continue;
                if (method.owner.equals(call.owner) || call.getOpcode() != Opcodes.INVOKESTATIC
                        && (sources.subtype(method.owner, call.owner) || sources.subtype(call.owner, method.owner)))
                    fields.addAll(entry.getValue());
            }
            Set<FieldId> result = Set.copyOf(fields);
            calls.put(id, result);
            return result;
        }

        private void inspect(ClassNode owner) throws IOException {
            for (MethodNode method : owner.methods) {
                Map<MethodInsnNode, Set<AbstractInsnNode>> returned = new IdentityHashMap<>();
                Map<AbstractInsnNode, FieldId> fields = new IdentityHashMap<>();
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof FieldInsnNode read) {
                        if (!(read.desc.equals(MAP) || read.desc.equals(LIST) || read.desc.equals(SET))) continue;
                        FieldId id = field(read);
                        if (id != null && candidates.containsKey(id)) fields.put(read, id);
                    } else if (instruction instanceof MethodInsnNode call
                            && (Type.getReturnType(call.desc).getDescriptor().equals(MAP)
                            || Type.getReturnType(call.desc).getDescriptor().equals(LIST))) {
                        Set<AbstractInsnNode> aliases = new LinkedHashSet<>();
                        for (FieldId id : getter(call)) {
                            FieldInsnNode origin = id.origin();
                            aliases.add(origin);
                            fields.put(origin, id);
                        }
                        if (!aliases.isEmpty()) returned.put(call, aliases);
                    }
                }
                if (fields.isEmpty()) continue;
                Origins interpreter = new Origins(returned);
                Frame<SourceValue>[] frames = analyze(owner, method, interpreter);
                if (frames == null || sources.ambiguous(owner.name)) {
                    fields.values().forEach(id -> candidates.get(id).rejected = true);
                    continue;
                }
                List<AbstractInsnNode> code = boundedInstructions(method);
                for (int i = 0; i < code.size(); i++) {
                    if (!(code.get(i) instanceof FieldInsnNode write)
                            || !(write.getOpcode() == Opcodes.PUTFIELD || write.getOpcode() == Opcodes.PUTSTATIC)) continue;
                    FieldId id = fields.get(write);
                    if (id == null) continue;
                    Candidate candidate = candidates.get(id);
                    candidate.assignments++;
                    if (!write.owner.equals(id.owner) || !emptyAllocation(code, i, candidate.backing)) candidate.rejected = true;
                }
                interpreter.observer = (instruction, operands) -> observe(owner, method, instruction, operands, fields,
                        interpreter.parameterTypes);
                int index = 0;
                for (AbstractInsnNode instruction : method.instructions) {
                    Frame<SourceValue> frame = frames[index++];
                    if (frame == null || instruction.getOpcode() < 0) continue;
                    try { new Frame<>(frame).execute(instruction, interpreter); }
                    catch (AnalyzerException failure) { fields.values().forEach(id -> candidates.get(id).rejected = true); }
                }
            }
        }

        private void observe(ClassNode owner, MethodNode method, AbstractInsnNode instruction,
                             List<? extends SourceValue> operands, Map<AbstractInsnNode, FieldId> origins,
                             Map<AbstractInsnNode, String> parameterTypes) {
            for (int i = 0; i < operands.size(); i++) {
                SourceValue value = operands.get(i);
                if (value == null) continue;
                Set<FieldId> seen = new HashSet<>();
                for (AbstractInsnNode source : value.insns) {
                    FieldId id = origins.get(source);
                    if (id == null || !seen.add(id)) continue;
                    Candidate candidate = candidates.get(id);
                    if (instruction instanceof MethodInsnNode call && i == 0 && call.getOpcode() != Opcodes.INVOKESTATIC) {
                        operation(candidate, call, operands, parameterTypes);
                    } else if (instruction.getOpcode() == Opcodes.ARETURN && candidate.kind == Kind.WEAK_ENTITY
                            && getters.getOrDefault(new MethodId(owner.name, method.name, method.desc), Set.of()).contains(id)) {
                        // Getter callers are analyzed with these same field origins.
                    } else if (instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                            && (cast.desc.equals("java/util/Map") && id.descriptor.equals(MAP)
                            || (cast.desc.equals("java/util/List") || cast.desc.equals("java/util/Collection"))
                            && id.descriptor.equals(LIST)
                            || (cast.desc.equals("java/util/Set") || cast.desc.equals("java/util/Collection"))
                            && id.descriptor.equals(SET))) {
                        // Interface casts retain the collection's public contract.
                    } else candidate.rejected = true;
                }
            }
        }

        private void operation(Candidate candidate, MethodInsnNode call, List<? extends SourceValue> operands,
                               Map<AbstractInsnNode, String> parameterTypes) {
            boolean map = candidate.kind != Kind.QUEUE && candidate.kind != Kind.KEY_SET;
            if (!(map ? call.owner.equals("java/util/Map")
                    : call.owner.equals(candidate.kind == Kind.KEY_SET ? "java/util/Set" : "java/util/List")
                    || call.owner.equals("java/util/Collection"))) {
                candidate.rejected = true;
                return;
            }
            String operation = call.name + call.desc;
            if (candidate.kind == Kind.CACHE || candidate.kind == Kind.SCALAR_MAP) {
                if (CACHE_READS.contains(operation) || candidate.kind == Kind.SCALAR_MAP
                        && operation.equals("put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")) {
                    if (operands.size() > 1 && !immutableKey(operands.get(1), candidate.key, parameterTypes)) candidate.rejected = true;
                    candidate.operation = true;
                    return;
                }
                if (!operation.equals(COMPUTE) || operands.size() != 3) { candidate.rejected = true; return; }
                if (!immutableKey(operands.get(1), candidate.key, parameterTypes)) candidate.rejected = true;
                Set<Handle> factories = lambdas(operands.get(2));
                if (factories.isEmpty()) candidate.rejected = true;
                candidate.factories.addAll(factories);
            } else if (candidate.kind == Kind.KEY_SET) {
                if (!SET_OPERATIONS.contains(operation)
                        || operands.size() > 1 && !immutableKey(operands.get(1), candidate.key, parameterTypes)) candidate.rejected = true;
            } else if (candidate.kind == Kind.WEAK_ENTITY) {
                if (!WEAK_OPERATIONS.contains(operation)) candidate.rejected = true;
            } else {
                if (!QUEUE_OPERATIONS.contains(operation)) candidate.rejected = true;
                if (operation.equals("removeIf(Ljava/util/function/Predicate;)Z")) {
                    Set<Handle> predicates = lambdas(operands.get(1));
                    if (predicates.isEmpty() || predicates.stream().anyMatch(handle -> handle.getTag() != Opcodes.H_INVOKESTATIC
                            || !handle.getDesc().equals("(L" + candidate.key + ";)Z"))) candidate.rejected = true;
                    candidate.removal = true;
                }
            }
            candidate.operation = true;
        }

        private static boolean immutableKey(SourceValue value, String key, Map<AbstractInsnNode, String> parameters) {
            if (value.insns.isEmpty()) return false;
            for (AbstractInsnNode origin : value.insns) {
                if (origin.getOpcode() == Opcodes.ACONST_NULL) continue;
                String type = parameters.get(origin);
                if (origin instanceof LdcInsnNode constant && constant.cst instanceof String) type = "java/lang/String";
                else if (origin instanceof MethodInsnNode call) type = referenceType(Type.getReturnType(call.desc));
                else if (origin instanceof InvokeDynamicInsnNode dynamic) type = referenceType(Type.getReturnType(dynamic.desc));
                else if (origin instanceof FieldInsnNode field) type = referenceType(Type.getType(field.desc));
                else if (origin instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW) type = allocation.desc;
                if (!key.equals(type)) return false;
            }
            return true;
        }

        private static Set<Handle> lambdas(SourceValue value) {
            Set<Handle> result = new HashSet<>();
            for (AbstractInsnNode source : value.insns) {
                if (!(source instanceof InvokeDynamicInsnNode dynamic)
                        || !dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                        || Type.getArgumentTypes(dynamic.desc).length != 0) return Set.of();
                for (Object argument : dynamic.bsmArgs) if (argument instanceof Handle handle) result.add(handle);
            }
            return result;
        }

        private static Frame<SourceValue>[] analyze(ClassNode owner, MethodNode method, Origins interpreter) {
            if (boundedInstructions(method) == null) return null;
            try { return new Analyzer<>(interpreter).analyze(owner.name, method); }
            catch (AnalyzerException failure) { return null; }
        }
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, String descriptor) {
        return annotations != null && annotations.stream().anyMatch(annotation -> annotation.desc.equals(descriptor));
    }

    private static String mixinTarget(ClassNode owner) {
        Set<String> targets = mixinTargets(owner);
        return targets.size() == 1 ? targets.iterator().next() : null;
    }

    private static Set<String> mixinTargets(ClassNode owner) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (owner.visibleAnnotations != null) annotations.addAll(owner.visibleAnnotations);
        if (owner.invisibleAnnotations != null) annotations.addAll(owner.invisibleAnnotations);
        Set<String> targets = new HashSet<>();
        for (AnnotationNode annotation : annotations) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || annotation.values == null) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) {
                Object name = annotation.values.get(i);
                if (!(name.equals("value") || name.equals("targets"))
                        || !(annotation.values.get(i + 1) instanceof List<?> values)) continue;
                for (Object value : values) {
                    if (value instanceof Type type) targets.add(type.getInternalName());
                    else if (value instanceof String type) targets.add(type.replace('.', '/'));
                }
            }
        }
        return targets;
    }

    private static String referenceType(Type type) {
        return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
    }

    @FunctionalInterface
    private interface Observer { void accept(AbstractInsnNode instruction, List<? extends SourceValue> operands); }

    private static final class Origins extends SourceInterpreter {
        final Map<MethodInsnNode, Set<AbstractInsnNode>> returned;
        final Map<AbstractInsnNode, String> parameterTypes = new IdentityHashMap<>();
        Observer observer;
        Origins(Map<MethodInsnNode, Set<AbstractInsnNode>> returned) { super(Opcodes.ASM9); this.returned = returned; }
        @Override public SourceValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
            if (type.getSort() != Type.OBJECT) return super.newParameterValue(isInstanceMethod, local, type);
            VarInsnNode origin = new VarInsnNode(Opcodes.ALOAD, local);
            parameterTypes.put(origin, type.getInternalName());
            return new SourceValue(1, origin);
        }
        @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
        @Override public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
            if (observer != null) observer.accept(instruction, List.of(value));
            return instruction.getOpcode() == Opcodes.CHECKCAST ? value : super.unaryOperation(instruction, value);
        }
        @Override public SourceValue binaryOperation(AbstractInsnNode instruction, SourceValue left, SourceValue right) {
            if (observer != null) observer.accept(instruction, List.of(left, right));
            return super.binaryOperation(instruction, left, right);
        }
        @Override public SourceValue ternaryOperation(AbstractInsnNode instruction, SourceValue a, SourceValue b, SourceValue c) {
            if (observer != null) observer.accept(instruction, List.of(a, b, c));
            return super.ternaryOperation(instruction, a, b, c);
        }
        @Override public SourceValue naryOperation(AbstractInsnNode instruction, List<? extends SourceValue> values) {
            if (observer != null) observer.accept(instruction, values);
            if (instruction instanceof MethodInsnNode call && returned.containsKey(call))
                return new SourceValue(1, returned.get(call));
            return super.naryOperation(instruction, values);
        }
    }
}
