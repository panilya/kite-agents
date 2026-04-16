package io.kite;

import java.util.Objects;
import java.util.function.BiFunction;

public final class InputGuardBuilder<T> {

    private final String name;
    private Guard.Mode mode = Guard.Mode.BLOCKING;
    private StreamBehavior streamBehavior = StreamBehavior.BUFFER;

    InputGuardBuilder(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public InputGuardBuilder<T> blocking() {
        this.mode = Guard.Mode.BLOCKING;
        return this;
    }

    public InputGuardBuilder<T> parallel() {
        this.mode = Guard.Mode.PARALLEL;
        return this;
    }

    public InputGuardBuilder<T> streamBehavior(StreamBehavior behavior) {
        this.streamBehavior = Objects.requireNonNull(behavior, "behavior");
        return this;
    }

    /** Check using typed context + input text. */
    public Guard<T> check(BiFunction<T, String, GuardResult> fn) {
        Objects.requireNonNull(fn, "check function");
        return new Guard<>(name, Guard.Phase.INPUT, mode, streamBehavior, fn);
    }

    /** Check using only the input text (ignores context — useful for context-free guards on typed agents). */
    public Guard<T> check(java.util.function.Function<String, GuardResult> fn) {
        Objects.requireNonNull(fn, "check function");
        return new Guard<>(name, Guard.Phase.INPUT, mode, streamBehavior, (ctx, s) -> fn.apply(s));
    }
}
