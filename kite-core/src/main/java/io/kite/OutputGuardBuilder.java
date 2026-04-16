package io.kite;

import java.util.Objects;
import java.util.function.BiFunction;

public final class OutputGuardBuilder<T> {

    private final String name;

    OutputGuardBuilder(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public Guard<T> check(BiFunction<T, String, GuardResult> fn) {
        Objects.requireNonNull(fn, "check function");
        return new Guard<>(name, Guard.Phase.OUTPUT, Guard.Mode.BLOCKING, StreamBehavior.BUFFER, fn);
    }

    public Guard<T> check(java.util.function.Function<String, GuardResult> fn) {
        Objects.requireNonNull(fn, "check function");
        return new Guard<>(name, Guard.Phase.OUTPUT, Guard.Mode.BLOCKING, StreamBehavior.BUFFER, (ctx, s) -> fn.apply(s));
    }
}
