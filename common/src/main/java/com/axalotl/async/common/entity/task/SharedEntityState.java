package com.axalotl.async.common.entity.task;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import com.axalotl.async.common.platform.PlatformUtils;
import com.axalotl.async.common.ParallelProcessor;
import com.axalotl.async.common.bootstrap.SharedWeakMapExtension;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.TextFilter;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.tree.FieldNode;

/** Keeps direct shared mutable state and nested NBT aliases under a common callback owner. */
final class SharedEntityState {
    // Natural orderings only cache equivalent wrappers; they have no independent mutable sort state.
    private static final Object NATURAL_ORDERING = Ordering.natural();
    private static final Object REVERSE_NATURAL_ORDERING = Ordering.natural().reverse();
    private enum EmptyCheck { NONE, POTION, DAMAGE_SOURCE, OPTIONAL, IMMUTABLE_COLLECTION, IMMUTABLE_MAP, COLLECTION, MAP, ARRAY }

    private record ScanType(boolean metadata, boolean ownerRead, boolean children, EmptyCheck emptyCheck,
                            FixedStateReferences references) {}

    private static final ClassValue<ScanType> TYPES = new ClassValue<>() {
        @Override protected ScanType computeValue(Class<?> type) {
            FixedStateReferences container = FixedStateReferences.container(type);
            boolean metadata = isMetadataType(type) || Entity.class.isAssignableFrom(type)
                    || CooperativeTask.class.isAssignableFrom(type) || Enum.class.isAssignableFrom(type)
                    || type == BlockPos.class || type == SoundType.class
                    || PlatformUtils.isSharedEntityMetadataType(type)
                    || type.getName().startsWith("java.util.Collections$Empty");
            boolean children = CompoundTag.class.isAssignableFrom(type) || ListTag.class.isAssignableFrom(type)
                    || ByteArrayTag.class.isAssignableFrom(type) || IntArrayTag.class.isAssignableFrom(type)
                    || LongArrayTag.class.isAssignableFrom(type) || ItemStack.class.isAssignableFrom(type);
            boolean ownerRead = container.fixed() || ItemStack.class.isAssignableFrom(type) || type == Potion.class || type == DamageSource.class
                    || ImmutableCollection.class.isAssignableFrom(type) || ImmutableMap.class.isAssignableFrom(type)
                    || CompoundTag.class.isAssignableFrom(type) && type != CompoundTag.class
                    || ListTag.class.isAssignableFrom(type) && type != ListTag.class
                    || ByteArrayTag.class.isAssignableFrom(type) && type != ByteArrayTag.class
                    || IntArrayTag.class.isAssignableFrom(type) && type != IntArrayTag.class
                    || LongArrayTag.class.isAssignableFrom(type) && type != LongArrayTag.class;
            EmptyCheck emptyCheck;
            if (type == Potion.class) emptyCheck = EmptyCheck.POTION;
            else if (type == DamageSource.class) emptyCheck = EmptyCheck.DAMAGE_SOURCE;
            else if (Optional.class.isAssignableFrom(type)) emptyCheck = EmptyCheck.OPTIONAL;
            else if (ImmutableCollection.class.isAssignableFrom(type)) emptyCheck = EmptyCheck.IMMUTABLE_COLLECTION;
            else if (ImmutableMap.class.isAssignableFrom(type)) emptyCheck = EmptyCheck.IMMUTABLE_MAP;
            else if (type.isArray()) emptyCheck = EmptyCheck.ARRAY;
            else if (type.getName().startsWith("java.util.ImmutableCollections$")) {
                if (Collection.class.isAssignableFrom(type)) emptyCheck = EmptyCheck.COLLECTION;
                else if (Map.class.isAssignableFrom(type)) emptyCheck = EmptyCheck.MAP;
                else emptyCheck = EmptyCheck.NONE;
            } else emptyCheck = EmptyCheck.NONE;
            FixedStateReferences references = container.fixed() ? container
                    : !metadata && !ownerRead && !children && emptyCheck == EmptyCheck.NONE
                            ? FixedStateReferences.inspect(type, SharedEntityState::isMetadataType) : FixedStateReferences.OPAQUE;
            return new ScanType(metadata, ownerRead, children, emptyCheck, references);
        }
    };

    private record EntityFields(MethodHandle[] getters, String[] names) {}

    private static final ClassValue<EntityFields> FIELDS = new ClassValue<>() {
        @Override protected EntityFields computeValue(Class<?> type) {
            ArrayList<MethodHandle> fields = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            for (Class<?> owner = type; owner != null && Entity.class.isAssignableFrom(owner); owner = owner.getSuperclass()) {
                Field[] declared;
                try { declared = owner.getDeclaredFields(); }
                catch (NoClassDefFoundError missing) {
                    addTransformedFields(fields, names, owner, missing);
                    continue;
                }
                for (Field field : declared) {
                    if (Modifier.isStatic(field.getModifiers()) || excludedFieldType(field.getType())) continue;
                    // Unknown mod fields cannot be declared in a fixed AT/AW. Resolve access once per class.
                    if (field.trySetAccessible()) {
                        try {
                            fields.add(MethodHandles.lookup().unreflectGetter(field)
                                    .asType(MethodType.methodType(Object.class, Entity.class)));
                            names.add(owner.getName() + "." + field.getName());
                        } catch (IllegalAccessException failure) {
                            throw new IllegalStateException("Entity field access changed: " + field, failure);
                        }
                    } else LogManager.getLogger().warn("Cannot inspect shared entity field {}.{}", owner.getName(), field.getName());
                }
            }
            return new EntityFields(fields.toArray(MethodHandle[]::new), names.toArray(String[]::new));
        }
    };

    private static boolean excludedFieldType(Class<?> type) {
        return type.isPrimitive() || isMetadataType(type) || Entity.class.isAssignableFrom(type)
                || CooperativeTask.class.isAssignableFrom(type);
    }

    private static void addTransformedFields(ArrayList<MethodHandle> fields, ArrayList<String> names, Class<?> owner,
                                             NoClassDefFoundError reflectionFailure) {
        List<FieldNode> definitions = SharedWeakMapExtension.transformedFields(owner);
        if (definitions == null) {
            throw new IllegalStateException("No transformed field layout for " + owner.getName(), reflectionFailure);
        }
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try { lookup = MethodHandles.privateLookupIn(owner, lookup); }
        catch (IllegalAccessException inaccessible) {
            LogManager.getLogger().warn("Cannot privately inspect entity fields in {}", owner.getName());
        }
        for (FieldNode definition : definitions) {
            if (Modifier.isStatic(definition.access)) continue;
            Class<?> fieldType;
            try {
                fieldType = MethodType.fromMethodDescriptorString("()" + definition.desc, owner.getClassLoader())
                        .returnType();
            } catch (TypeNotPresentException | NoClassDefFoundError unavailable) {
                // A dedicated server cannot hold an instance of a client-only field type.
                LogManager.getLogger().warn("Skipping unavailable entity field {}.{} ({})",
                        owner.getName(), definition.name, definition.desc);
                continue;
            }
            if (excludedFieldType(fieldType)) continue;
            try {
                fields.add(lookup.findGetter(owner, definition.name, fieldType)
                        .asType(MethodType.methodType(Object.class, Entity.class)));
                names.add(owner.getName() + "." + definition.name);
            } catch (NoSuchFieldException mismatch) {
                throw new IllegalStateException("Transformed entity field changed: " + owner.getName()
                        + "." + definition.name, mismatch);
            } catch (IllegalAccessException inaccessible) {
                LogManager.getLogger().warn("Cannot inspect shared entity field {}.{}",
                        owner.getName(), definition.name);
            }
        }
    }

    private static final ThreadLocal<Scan> SCANS = ThreadLocal.withInitial(Scan::new);

    private SharedEntityState() {}

    static Map<CooperativeTask, Map<String, Integer>> bind(Map<Entity, CooperativeTask> owners,
                                                        Map<Entity, Entity> sources, boolean recording) {
        Scan scan = SCANS.get();
        if (scan.active) scan = new Scan();
        scan.active = true;
        scan.recording = recording;
        try {
            IdentityHashMap<CooperativeTask, Domain> domains = scan.domains;
            for (CooperativeTask task : owners.values()) domains.computeIfAbsent(task, Domain::new);
            if (domains.size() < 2) return Map.of();
            for (var entry : owners.entrySet()) {
                scan.entities.add(entry.getKey());
                scan.entityDomains.add(domains.get(entry.getValue()));
            }
            List<Runnable> work = new ArrayList<>();
            int slice = (scan.entities.size() + scan.captures.length - 1) / scan.captures.length;
            for (int i = 0; i < scan.captures.length; i++) {
                int first = i * slice;
                int end = Math.min(first + slice, scan.entities.size());
                if (first >= end) break;
                Capture capture = scan.captures[i];
                Scan input = scan;
                work.add(() -> capture.read(input, first, end));
            }
            ParallelProcessor.prepareOwnership(work);
            work.clear();
            for (Capture capture : scan.captures) {
                References deferred = capture.deferred;
                for (int i = 0; i < deferred.size; i++) {
                    scan.ownerCapture.origin = deferred.origins == null ? null : deferred.origins[i];
                    scan.ownerCapture.capture(deferred.resources[i], deferred.owners[i]);
                }
            }
            for (int i = 0; i < scan.shards.length; i++) {
                int shard = i;
                Scan input = scan;
                work.add(() -> input.shards[shard].index(input, shard));
            }
            ParallelProcessor.prepareOwnership(work);
            int remaining = domains.size();
            List<Binding> bindings = recording ? new ArrayList<>() : null;
            for (var source : sources.entrySet()) {
                Domain actor = domains.get(owners.get(source.getKey()));
                Domain owner = domains.get(owners.get(source.getValue()));
                int merged = actor.union(owner);
                remaining -= merged;
                if (recording && merged != 0) bindings.add(new Binding(actor, "<entity owner>"));
            }
            merge: for (Shard shard : scan.shards) {
                for (int i = 0; i < shard.size; i++) {
                    int merged = shard.owners[i].union((Domain) shard.resources[i]);
                    remaining -= merged;
                    if (recording && merged != 0) bindings.add(new Binding(shard.owners[i], shard.bindings.get(i)));
                    if (remaining == 1) break merge;
                }
            }
            owners.replaceAll((entity, task) -> domains.get(task).root().task);
            if (!recording) return Map.of();
            Map<CooperativeTask, Map<String, Integer>> origins = new IdentityHashMap<>();
            for (Binding binding : bindings) origins.computeIfAbsent(binding.domain().root().task,
                    ignored -> new HashMap<>()).merge(binding.origin(), 1, Integer::sum);
            origins.replaceAll((task, values) -> Map.copyOf(values));
            return Collections.unmodifiableMap(origins);
        } finally {
            scan.domains.clear();
            scan.entities.clear();
            scan.entityDomains.clear();
            scan.ownerCapture.clear();
            for (Capture capture : scan.captures) capture.clear();
            for (Shard shard : scan.shards) shard.clear();
            scan.recording = false;
            scan.active = false;
        }
    }

    private static final class Scan {
        final IdentityHashMap<CooperativeTask, Domain> domains = new IdentityHashMap<>();
        final ArrayList<Entity> entities = new ArrayList<>();
        final ArrayList<Domain> entityDomains = new ArrayList<>();
        final Capture[] captures = new Capture[32];
        final Shard[] shards = new Shard[32];
        final Capture ownerCapture = new Capture(true);
        boolean active;
        boolean recording;

        Scan() {
            Arrays.setAll(captures, ignored -> new Capture(false));
            Arrays.setAll(shards, ignored -> new Shard());
        }
    }

    private static class References {
        Object[] resources = new Object[64];
        Domain[] owners = new Domain[64];
        String[] origins;
        int size;

        void add(Object resource, Domain owner) {
            add(resource, owner, null);
        }

        void add(Object resource, Domain owner, String origin) {
            if (size == resources.length) {
                resources = Arrays.copyOf(resources, size * 2);
                owners = Arrays.copyOf(owners, size * 2);
                if (origins != null) origins = Arrays.copyOf(origins, size * 2);
            }
            if (origin != null && origins == null) origins = new String[resources.length];
            if (origins != null) origins[size] = origin;
            resources[size] = resource;
            owners[size++] = owner;
        }

        void clear() {
            Arrays.fill(resources, 0, size, null);
            Arrays.fill(owners, 0, size, null);
            origins = null;
            size = 0;
        }
    }

    /** Each worker owns its complete scan buffers; virtual extension reads are deferred to the caller. */
    private static final class Capture {
        final References[] shards = new References[32];
        final References deferred = new References();
        final IdentityHashMap<Object, Boolean> expanded = new IdentityHashMap<>();
        final IdentityHashMap<Object, FixedStateReferences.Values> fixedValues = new IdentityHashMap<>();
        final IdentityHashMap<Object, Domain> fixedDomains = new IdentityHashMap<>();
        final ArrayDeque<Object> children = new ArrayDeque<>();
        final boolean ownerThread;
        String origin;

        Capture(boolean ownerThread) {
            this.ownerThread = ownerThread;
            Arrays.setAll(shards, ignored -> new References());
        }

        void read(Scan input, int first, int end) {
            try {
                for (int i = first; i < end; i++) {
                    Entity entity = input.entities.get(i);
                    Domain domain = input.entityDomains.get(i);
                    EntityFields layout = FIELDS.get(entity.getClass());
                    MethodHandle[] getters = layout.getters();
                    for (int field = 0; field < getters.length; field++) {
                        origin = input.recording ? layout.names()[field] : null;
                        Object resource;
                        try { resource = (Object) getters[field].invokeExact(entity); }
                        catch (RuntimeException | Error failure) { throw failure; }
                        catch (Throwable failure) { throw new IllegalStateException("Cannot read entity ownership state", failure); }
                        if (resource != null) capture(resource, domain);
                    }
                }
            } finally {
                clearTraversal();
            }
        }

        void capture(Object value, Domain domain) {
            captureOne(value, domain);
            while (!children.isEmpty()) captureOne(children.removeFirst(), domain);
        }

        private void captureOne(Object value, Domain domain) {
            if (value == ItemStack.EMPTY || value == EntityInLevelCallback.NULL || value == TextFilter.DUMMY) return;
            if (value == NATURAL_ORDERING || value == REVERSE_NATURAL_ORDERING) return;
            ScanType scanType = TYPES.get(value.getClass());
            if (!ownerThread && scanType.ownerRead()) {
                deferred.add(value, domain, origin);
                return;
            }
            if (scanType.metadata()) return;
            // Empty sorted containers can still reach a mutable comparator.
            if (!scanType.references().fixed() && !isMutableValue(value, scanType.emptyCheck())) return;
            if (scanType.references().fixed()) {
                FixedStateReferences.Values references = fixedValues.get(value);
                if (references == null) {
                    references = scanType.references().read(value);
                    fixedValues.put(value, references);
                }
                // Expand for each owner, including holders whose entity references keep them opaque.
                if (fixedDomains.put(value, domain) != domain) {
                    for (Object child : references.references()) if (child != null) children.add(child);
                }
                if (references.fixed()) return;
            }
            int hash = System.identityHashCode(value);
            shards[(hash ^ (hash >>> 16)) & (shards.length - 1)].add(value, domain, origin);
            if (!scanType.children()) return;
            // Keep every ownership edge, but expand each local shared/cyclic topology only once.
            if (expanded.put(value, Boolean.TRUE) == null) addTagChildren(value, children);
        }

        void clear() {
            for (References shard : shards) shard.clear();
            deferred.clear();
            clearTraversal();
        }

        private void clearTraversal() {
            expanded.clear();
            fixedValues.clear();
            fixedDomains.clear();
            children.clear();
            origin = null;
        }
    }

    /** Workers see references and domains only; no entity, collection or mod methods are called here. */
    private static final class Shard extends References {
        final IdentityHashMap<Object, Domain> index = new IdentityHashMap<>();
        List<String> bindings;
        IdentityHashMap<Object, String> provenance;
        IdentityHashMap<Object, String> originFields;

        void index(Scan input, int shard) {
            index(input.ownerCapture.shards[shard], input.recording, shard);
            for (Capture capture : input.captures) index(capture.shards[shard], input.recording, shard);
            index.clear();
        }

        private void index(References input, boolean recording, int shard) {
            try {
                for (int i = 0; i < input.size; i++) {
                    Domain owner = input.owners[i];
                    Domain previous = index.put(input.resources[i], owner);
                    String field = null;
                    String previousField = null;
                    if (recording) {
                        if (originFields == null) originFields = new IdentityHashMap<>();
                        field = input.origins == null ? "<direct capture>" : input.origins[i];
                        previousField = originFields.put(input.resources[i], field);
                    }
                    if (previous != null && previous != owner) {
                        add(previous, owner);
                        if (recording) {
                            if (bindings == null) bindings = new ArrayList<>();
                            if (provenance == null) provenance = new IdentityHashMap<>();
                            String identity = provenance.get(input.resources[i]);
                            if (identity == null) {
                                identity = input.resources[i].getClass().getName() + "@s" + shard + ":r" + provenance.size();
                                provenance.put(input.resources[i], identity);
                            }
                            bindings.add(identity + " {" + previousField + " <-> " + field + "}");
                        }
                    }
                }
            } finally {
                input.clear();
            }
        }

        @Override void clear() {
            index.clear();
            bindings = null;
            provenance = null;
            originFields = null;
            super.clear();
        }
    }

    private record Binding(Domain domain, String origin) {}

    private static boolean isMetadataType(Class<?> type) {
        return type == String.class || type == UUID.class || type.isEnum() || type == Class.class
                || type == Boolean.class || type == Character.class || type == Byte.class || type == Short.class
                || type == Integer.class || type == Long.class || type == Float.class || type == Double.class
                || Level.class.isAssignableFrom(type) || MinecraftServer.class.isAssignableFrom(type)
                || ResourceLocation.class.isAssignableFrom(type) || ResourceKey.class.isAssignableFrom(type)
                || TagKey.class.isAssignableFrom(type) || EntityType.class.isAssignableFrom(type)
                || Attribute.class.isAssignableFrom(type) || Item.class.isAssignableFrom(type)
                || Block.class.isAssignableFrom(type) || SoundEvent.class.isAssignableFrom(type)
                || MobEffect.class.isAssignableFrom(type) || EntityDimensions.class.isAssignableFrom(type)
                || ChunkPos.class.isAssignableFrom(type) || StateHolder.class.isAssignableFrom(type)
                || Vec3.class.isAssignableFrom(type) || NumericTag.class.isAssignableFrom(type)
                || StringTag.class.isAssignableFrom(type) || EndTag.class.isAssignableFrom(type);
    }

    private static void addTagChildren(Object value, ArrayDeque<Object> children) {
        if (value instanceof CompoundTag compound) {
            if (value.getClass() == CompoundTag.class && compound.isEmpty()) return;
            for (String key : compound.getAllKeys()) {
                Object child = compound.get(key);
                addMutableTagChild(child, children);
            }
        } else if (value instanceof ListTag list) {
            if (value.getClass() != ListTag.class) children.addAll(list);
            else if (!list.isEmpty()) {
                for (Object child : list) addMutableTagChild(child, children);
            }
        } else if (value instanceof ByteArrayTag array) children.add(array.getAsByteArray());
        else if (value instanceof IntArrayTag array) children.add(array.getAsIntArray());
        else if (value instanceof LongArrayTag array) children.add(array.getAsLongArray());
        else if (value instanceof ItemStack stack && stack.getTag() != null) children.add(stack.getTag());
    }

    private static void addMutableTagChild(Object child, ArrayDeque<Object> children) {
        // These immutable tag classes are also excluded by isMetadataType; no ownership edge is removed.
        if (child != null && !(child instanceof NumericTag || child instanceof StringTag || child instanceof EndTag))
            children.add(child);
    }

    private static boolean isMutableValue(Object value, EmptyCheck emptyCheck) {
        return switch (emptyCheck) {
            case NONE -> true;
            case POTION -> !((Potion) value).getEffects().isEmpty();
            case DAMAGE_SOURCE -> {
                DamageSource source = (DamageSource) value;
                yield source.getEntity() != null || source.getDirectEntity() != null;
            }
            case OPTIONAL -> !((Optional<?>) value).isEmpty();
            case IMMUTABLE_COLLECTION -> !((ImmutableCollection<?>) value).isEmpty();
            case IMMUTABLE_MAP -> !((ImmutableMap<?, ?>) value).isEmpty();
            case COLLECTION -> !((Collection<?>) value).isEmpty();
            case MAP -> !((Map<?, ?>) value).isEmpty();
            case ARRAY -> Array.getLength(value) != 0;
        };
    }

    private static final class Domain {
        final CooperativeTask task;
        Domain parent = this;
        int rank;

        Domain(CooperativeTask task) { this.task = task; }

        Domain root() {
            if (parent != this) parent = parent.root();
            return parent;
        }

        int union(Domain other) {
            Domain left = root(), right = other.root();
            if (left == right) return 0;
            if (left.rank < right.rank) left.parent = right;
            else {
                right.parent = left;
                if (left.rank == right.rank) left.rank++;
            }
            return 1;
        }
    }
}
