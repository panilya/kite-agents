package io.kite.guards;

import java.time.Duration;
import java.util.Map;

/**
 * Runtime record of one guard check: which guard ran, in which phase, with what verdict, and
 * how long it took. Constructed by the executor after a {@link GuardDecision} comes back from
 * the guard function.
 */
public record GuardOutcome(
        String name,
        GuardPhase phase,
        GuardDecision decision,
        Duration elapsed
) {

    public boolean blocked() {
        return decision instanceof GuardDecision.Block;
    }

    /** Structured info from the decision, or null. */
    public Map<String, Object> info() {
        return decision.info();
    }
}
