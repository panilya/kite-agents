package io.kite.guards;

import io.kite.StreamBehavior;

import java.util.function.Function;

/**
 * An input-phase guard: runs before the first LLM call, deciding whether the incoming user
 * input should be allowed. Carries {@link #mode()} (BLOCKING vs PARALLEL) and a
 * {@link StreamBehavior} for streaming runs. Build via {@link Guard#input(String)}.
 */
public record InputGuard<T>(
        String name,
        Mode mode,
        StreamBehavior streamBehavior,
        Function<InputGuardInput<T>, GuardDecision> check
) implements Guard<T> {

    public enum Mode { BLOCKING, PARALLEL }

    @Override
    public GuardPhase phase() {
        return GuardPhase.INPUT;
    }

    @Override
    @SuppressWarnings("unchecked")
    public GuardDecision decide(GuardInput<?> input) {
        return check.apply((InputGuardInput<T>) input);
    }
}
