package io.kite.guards;

import java.util.function.Function;

/**
 * An output-phase guard: runs after the agent's final assistant text is assembled, deciding
 * whether to surface it to the caller. Sequential, in declaration order. Build via
 * {@link Guard#output(String)}.
 */
public record OutputGuard<T>(
        String name,
        Function<OutputGuardInput<T>, GuardDecision> check
) implements Guard<T> {

    @Override
    public GuardPhase phase() {
        return GuardPhase.OUTPUT;
    }

    @Override
    @SuppressWarnings("unchecked")
    public GuardDecision decide(GuardInput<?> input) {
        return check.apply((OutputGuardInput<T>) input);
    }
}
