package io.kite.guards;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;

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
    public JsonNode info() {
        return decision.info();
    }

    /** Human-readable block reason, or null on allow. */
    public String message() {
        return decision instanceof GuardDecision.Block b ? b.reason() : null;
    }
}
