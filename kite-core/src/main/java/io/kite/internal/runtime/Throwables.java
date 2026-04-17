package io.kite.internal.runtime;

import java.util.concurrent.CompletionException;

final class Throwables {

    private Throwables() {}

    static String describe(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    /** Peel off a {@link CompletionException} wrapper if it has a cause; otherwise return as-is. */
    static Throwable unwrapCompletion(Throwable t) {
        return (t instanceof CompletionException ce && ce.getCause() != null) ? ce.getCause() : t;
    }
}
