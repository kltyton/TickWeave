package com.axalotl.async.common.entity.query;

import java.util.List;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Class membership only; never caches an entity's mutable collision state. */
public final class CollisionClassFilter {
    private static volatile Methods methods;
    private static final ClassValue<Flags> FLAGS = new ClassValue<>() {
        @Override
        protected Flags computeValue(Class<?> type) {
            Methods names = methods;
            if (names == null) return new Flags(false, false);
            Class<?> base = type;
            while (base != null && !base.getName().equals(names.owner())) base = base.getSuperclass();
            if (base == null) return new Flags(false, false);
            try {
                return new Flags(names.constantFalse()
                        && type.getMethod(names.target()).getDeclaringClass() == base
                        && type.getMethod(names.spectator()).getDeclaringClass() == base
                        && matches(type, names.guards().targetAbsent(), names.guards().targetPresent()),
                        names.defaultSource()
                        && type.getMethod(names.source(), base).getDeclaringClass() == base
                        && matches(type, names.guards().sourceAbsent(), names.guards().sourcePresent())
                        && inheritsHandlers(type, base, names.guards().virtualHandlers()));
            } catch (NoSuchMethodException | SecurityException | LinkageError unavailable) {
                // Unknown mappings or access policy retain the complete native query.
                return new Flags(false, false);
            }
        }
    };

    private CollisionClassFilter() {
    }

    public static synchronized void configure(String owner, String target, String source, String spectator,
                                              boolean constantFalse, Guards guards) {
        Methods configured = new Methods(owner.replace('/', '.'), target, source, spectator,
                constantFalse, guards != null, guards == null ? Guards.EMPTY : guards);
        if (methods != null && !methods.equals(configured)) {
            throw new IllegalStateException("Collision classification cannot change after initialization");
        }
        methods = configured;
    }

    public static boolean canFilter(Class<?> source) {
        Methods names = methods;
        return names != null && names.constantFalse()
                && (source == null || FLAGS.get(source).defaultSource());
    }

    public static boolean mayCollide(Class<?> target) {
        return !FLAGS.get(target).constantFalse();
    }

    public static boolean isSourceReference(String owner, String method) {
        Methods names = methods;
        return names != null && names.owner().equals(owner) && names.source().equals(method);
    }

    private static boolean matches(Class<?> type, List<String> absent, List<String> present) {
        return absent.stream().noneMatch(name -> isType(type, name))
                && present.stream().allMatch(name -> isType(type, name));
    }

    private static boolean isType(Class<?> type, String name) {
        String binaryName = name.replace('/', '.');
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(binaryName)) return true;
            for (Class<?> implemented : current.getInterfaces()) if (isType(implemented, name)) return true;
        }
        return false;
    }

    private static boolean inheritsHandlers(Class<?> type, Class<?> base, List<String> handlers) {
        for (Class<?> current = type; current != base; current = current.getSuperclass()) {
            for (String handler : handlers) {
                try {
                    current.getDeclaredMethod(handler, base, CallbackInfoReturnable.class);
                    return false;
                } catch (NoSuchMethodException inherited) {
                    // Protected callback handlers are absent from Class.getMethod, so inspect each declaration.
                }
            }
        }
        return true;
    }

    public record Guards(List<String> sourceAbsent, List<String> sourcePresent,
                         List<String> targetAbsent, List<String> targetPresent, List<String> virtualHandlers) {
        public static final Guards EMPTY = new Guards(List.of(), List.of(), List.of(), List.of(), List.of());

        public Guards {
            sourceAbsent = List.copyOf(sourceAbsent);
            sourcePresent = List.copyOf(sourcePresent);
            targetAbsent = List.copyOf(targetAbsent);
            targetPresent = List.copyOf(targetPresent);
            virtualHandlers = List.copyOf(virtualHandlers);
        }
    }

    private record Methods(String owner, String target, String source, String spectator,
                           boolean constantFalse, boolean defaultSource, Guards guards) {
    }

    private record Flags(boolean constantFalse, boolean defaultSource) {
    }
}
