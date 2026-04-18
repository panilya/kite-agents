package io.kite.guards;

import java.util.Objects;
import java.util.function.Function;

public final class OutputGuardBuilder<T> {

    private final String name;

    OutputGuardBuilder(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public OutputGuard<T> check(Function<OutputGuardInput<T>, GuardDecision> fn) {
        Objects.requireNonNull(fn, "check function");
        return new OutputGuard<>(name, fn);
    }
}
