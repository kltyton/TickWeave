package com.axalotl.async.common.entity.task;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.stream.BaseStream;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/** Keeps lazy query traversal on the owner, including caller-added terminal operations. */
public final class OwnerStream {
    private OwnerStream() {
    }

    public static <T> Stream<T> wrap(Stream<T> stream) {
        @SuppressWarnings("unchecked")
        Stream<T> wrapped = (Stream<T>) wrapResult(stream);
        return wrapped;
    }

    private static Object wrapResult(Object result) {
        Class<?> contract;
        if (result instanceof Stream<?>) contract = Stream.class;
        else if (result instanceof IntStream) contract = IntStream.class;
        else if (result instanceof LongStream) contract = LongStream.class;
        else if (result instanceof DoubleStream) contract = DoubleStream.class;
        else if (result instanceof PrimitiveIterator.OfInt) contract = PrimitiveIterator.OfInt.class;
        else if (result instanceof PrimitiveIterator.OfLong) contract = PrimitiveIterator.OfLong.class;
        else if (result instanceof PrimitiveIterator.OfDouble) contract = PrimitiveIterator.OfDouble.class;
        else if (result instanceof Iterator<?>) contract = Iterator.class;
        else if (result instanceof Spliterator.OfInt) contract = Spliterator.OfInt.class;
        else if (result instanceof Spliterator.OfLong) contract = Spliterator.OfLong.class;
        else if (result instanceof Spliterator.OfDouble) contract = Spliterator.OfDouble.class;
        else if (result instanceof Spliterator<?>) contract = Spliterator.class;
        else return result;
        return Proxy.newProxyInstance(OwnerStream.class.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == arguments[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "OwnerStream[" + contract.getSimpleName() + "]";
                            default -> throw new IllegalStateException(method.getName());
                        };
                    }
                    return EntityTasks.onMain(() -> wrapResult(invoke(result, method, arguments)));
                });
    }

    private static Object invoke(Object target, Method method, Object[] arguments) {
        try {
            // POI sources mutate caches while reading and cannot traverse in a common pool.
            if (target instanceof BaseStream<?, ?> stream && method.getName().equals("parallel")) {
                return stream.sequential();
            }
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Query operation failed", cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot invoke public query operation", failure);
        }
    }
}
