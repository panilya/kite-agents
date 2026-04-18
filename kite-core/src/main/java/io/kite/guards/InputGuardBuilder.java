package io.kite.guards;

import io.kite.StreamBehavior;

import java.util.Objects;
import java.util.function.Function;

public final class InputGuardBuilder<T> {

    private final String name;
    private InputGuard.Mode mode = InputGuard.Mode.BLOCKING;
    private StreamBehavior streamBehavior = StreamBehavior.BUFFER;

    InputGuardBuilder(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public InputGuardBuilder<T> blocking() {
        this.mode = InputGuard.Mode.BLOCKING;
        return this;
    }

    public InputGuardBuilder<T> parallel() {
        this.mode = InputGuard.Mode.PARALLEL;
        return this;
    }

    public InputGuardBuilder<T> streamBehavior(StreamBehavior behavior) {
        this.streamBehavior = Objects.requireNonNull(behavior, "behavior");
        return this;
    }

    public InputGuard<T> check(Function<InputGuardInput<T>, GuardDecision> fn) {
        Objects.requireNonNull(fn, "check function");
        return new InputGuard<>(name, mode, streamBehavior, fn);
    }
}
