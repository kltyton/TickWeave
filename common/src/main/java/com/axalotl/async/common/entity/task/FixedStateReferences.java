package com.axalotl.async.common.entity.task;

import com.axalotl.async.common.bootstrap.SharedWeakMapExtension;
import com.axalotl.async.common.collection.FixedListSnapshot;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;

/** Separates fixed reference holders from the mutable objects they contain. */
final class FixedStateReferences {
    static final FixedStateReferences OPAQUE = new FixedStateReferences(false, new MethodHandle[0]);
    private static final Object[] EMPTY = new Object[0];
    private static final FixedStateReferences CONTAINER = new FixedStateReferences(true, new MethodHandle[0], true);
    // These factory classes have materialized membership; computed views retain their existing owner.
    private static final Set<Class<?>> CONTAINERS = Set.copyOf(List.of(
            ImmutableList.of().getClass(), ImmutableList.of(0).getClass(), ImmutableList.of(0, 1).getClass(),
            ImmutableSet.of().getClass(), ImmutableSet.of(0).getClass(), ImmutableSet.of(0, 1).getClass(),
            ImmutableMap.of().getClass(), ImmutableMap.of(0, 0).getClass(), ImmutableMap.of(0, 0, 1, 1).getClass(),
            ImmutableSortedSet.of(0, 1).getClass(), ImmutableSortedMap.of(0, 0).getClass(),
            List.of().getClass(), List.of(0).getClass(), Set.of().getClass(), Set.of(0).getClass(),
            Map.of().getClass(), Map.of(0, 0).getClass(), FixedListSnapshot.class));
    private final boolean fixed;
    private final MethodHandle[] fields;
    private final boolean container;

    private FixedStateReferences(boolean fixed, MethodHandle[] fields) {
        this(fixed, fields, false);
    }

    private FixedStateReferences(boolean fixed, MethodHandle[] fields, boolean container) {
        this.fixed = fixed;
        this.fields = fields;
        this.container = container;
    }

    static FixedStateReferences container(Class<?> type) {
        return CONTAINERS.contains(type) ? CONTAINER : OPAQUE;
    }

    static FixedStateReferences inspect(Class<?> type, Predicate<Class<?>> metadata) {
        if (type.isArray() || type.isInterface() || type.isHidden() || type.isSynthetic()
                || type.getName().startsWith("java.") || type.getName().startsWith("jdk.")) return OPAQUE;
        ArrayList<Field> references = new ArrayList<>();
        int count = 0;
        try {
            for (Class<?> owner = type; owner != Object.class && owner != null; owner = owner.getSuperclass()) {
                if (SharedWeakMapExtension.hasNativeMethods(owner)) return OPAQUE;
                for (Field field : owner.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    count++;
                    if (!Modifier.isFinal(field.getModifiers())) return OPAQUE;
                    Class<?> fieldType = field.getType();
                    if (Entity.class.isAssignableFrom(fieldType) || CooperativeTask.class.isAssignableFrom(fieldType)) return OPAQUE;
                    if (!fieldType.isPrimitive() && !metadata.test(fieldType)) references.add(field);
                }
            }
            if (count == 0) return OPAQUE;
            ArrayList<MethodHandle> getters = new ArrayList<>(references.size());
            for (Field field : references) {
                if (!field.trySetAccessible()) return OPAQUE;
                getters.add(MethodHandles.lookup().unreflectGetter(field)
                        .asType(MethodType.methodType(Object.class, Object.class)));
            }
            return new FixedStateReferences(true, getters.toArray(MethodHandle[]::new));
        } catch (NoClassDefFoundError missing) {
            // An instance layout can still declare a field type unavailable on a dedicated server.
            LogManager.getLogger().debug("Cannot inspect fixed state references in {}", type.getName(), missing);
            return OPAQUE;
        } catch (IllegalAccessException inaccessible) {
            throw new IllegalStateException("Fixed state field access changed in " + type.getName(), inaccessible);
        }
    }

    boolean fixed() { return fixed; }

    Values read(Object value) {
        if (container) return readContainer(value);
        if (fields.length == 0) return new Values(true, EMPTY);
        Object[] references = new Object[fields.length];
        boolean fixedReferences = true;
        for (int i = 0; i < fields.length; i++) {
            try { references[i] = (Object) fields[i].invokeExact(value); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Cannot read fixed state references", failure); }
            if (references[i] instanceof Entity || references[i] instanceof CooperativeTask) fixedReferences = false;
        }
        return new Values(fixedReferences, references);
    }

    private static Values readContainer(Object value) {
        Object[] references;
        if (value instanceof Map<?, ?> map) {
            ArrayList<Object> children = new ArrayList<>();
            for (var entry : map.entrySet()) {
                children.add(entry.getKey());
                children.add(entry.getValue());
            }
            if (map instanceof SortedMap<?, ?> sorted) children.add(sorted.comparator());
            references = children.toArray();
        } else {
            Collection<?> collection = (Collection<?>) value;
            if (collection instanceof SortedSet<?> sorted) {
                ArrayList<Object> children = new ArrayList<>(collection);
                children.add(sorted.comparator());
                references = children.toArray();
            } else references = collection.toArray();
        }
        boolean fixedReferences = true;
        for (Object child : references) {
            if (child instanceof Entity || child instanceof CooperativeTask) fixedReferences = false;
        }
        return new Values(fixedReferences, references);
    }

    record Values(boolean fixed, Object[] references) {}
}
