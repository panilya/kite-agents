package io.kite.internal.runtime;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Explicit context propagation. Avoids {@code InheritableThreadLocal} inheritance semantics
 * (which depend on JVM flags with virtual threads) by capturing the current value at submit time
 * and restoring it in the child via a wrapper closure.
 */
public final class ContextScope {

    private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();

    private ContextScope() {}

    public static <R> R runWith(Object ctx, Supplier<R> body) {
        Object prev = CURRENT.get();
        CURRENT.set(ctx);
        try {
            return body.get();
        } finally {
            if (prev == null) CURRENT.remove();
            else CURRENT.set(prev);
        }
    }

    public static void runWith(Object ctx, Runnable body) {
        Object prev = CURRENT.get();
        CURRENT.set(ctx);
        try {
            body.run();
        } finally {
            if (prev == null) CURRENT.remove();
            else CURRENT.set(prev);
        }
    }

    public static Object current() {
        return CURRENT.get();
    }

    public static Runnable capturing(Runnable r) {
        Object captured = CURRENT.get();
        return () -> {
            Object prev = CURRENT.get();
            CURRENT.set(captured);
            try {
                r.run();
            } finally {
                if (prev == null) CURRENT.remove();
                else CURRENT.set(prev);
            }
        };
    }

    public static <V> Callable<V> capturingCallable(Callable<V> c) {
        Object captured = CURRENT.get();
        return () -> {
            Object prev = CURRENT.get();
            CURRENT.set(captured);
            try {
                return c.call();
            } finally {
                if (prev == null) CURRENT.remove();
                else CURRENT.set(prev);
            }
        };
    }

    public static <V> Supplier<V> capturingSupplier(Supplier<V> s) {
        Object captured = CURRENT.get();
        return () -> {
            Object prev = CURRENT.get();
            CURRENT.set(captured);
            try {
                return s.get();
            } finally {
                if (prev == null) CURRENT.remove();
                else CURRENT.set(prev);
            }
        };
    }
}
