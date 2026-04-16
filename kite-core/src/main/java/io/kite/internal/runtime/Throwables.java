package io.kite.internal.runtime;

final class Throwables {

    private Throwables() {}

    static String describe(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
