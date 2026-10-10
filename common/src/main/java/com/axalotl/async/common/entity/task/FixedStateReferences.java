package com.axalotl.async.common.entity.task;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;

/** Separates fixed reference holders from the mutable objects they contain. */
final class FixedStateReferences {
    static final FixedStateReferences OPAQUE = new FixedStateReferences(false, new MethodHandle[0]);
    private static final Object[] EMPTY = new Object[0];
    private static final Values OPAQUE_VALUES = new Values(false, EMPTY);
    private final boolean fixed;
    private final MethodHandle[] fields;

    private FixedStateReferences(boolean fixed, MethodHandle[] fields) {
        this.fixed = fixed;
        this.fields = fields;
    }

    static FixedStateReferences inspect(Class<?> type, Predicate<Class<?>> metadata) {
        if (type.isArray() || type.isInterface() || type.isHidden() || type.isSynthetic()
                || type.getName().startsWith("java.") || type.getName().startsWith("jdk.")) return OPAQUE;
        ArrayList<Field> references = new ArrayList<>();
        int count = 0;
        try {
            for (Class<?> owner = type; owner != Object.class && owner != null; owner = owner.getSuperclass()) {
                for (var method : owner.getDeclaredMethods()) {
                    if (Modifier.isNative(method.getModifiers())) return OPAQUE;
                }
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
            // Server-side descriptors can still declare an unavailable client-only field or method type.
            LogManager.getLogger().debug("Cannot inspect fixed state references in {}", type.getName(), missing);
            return OPAQUE;
        } catch (IllegalAccessException inaccessible) {
            throw new IllegalStateException("Fixed state field access changed in " + type.getName(), inaccessible);
        }
    }

    boolean fixed() { return fixed; }

    Values read(Object value) {
        if (fields.length == 0) return new Values(true, EMPTY);
        Object[] references = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            try { references[i] = (Object) fields[i].invokeExact(value); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Cannot read fixed state references", failure); }
            if (references[i] instanceof Entity || references[i] instanceof CooperativeTask) return OPAQUE_VALUES;
        }
        return new Values(true, references);
    }

    record Values(boolean fixed, Object[] references) {}
}
