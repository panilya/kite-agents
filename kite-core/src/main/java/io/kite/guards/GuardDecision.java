package io.kite.guards;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The verdict a guard's check function produces. Sealed — a guard either {@link Allow}s
 * or {@link Block}s the run. Both variants may carry structured {@code info} for audit,
 * explainability, or observability dashboards.
 */
public sealed interface GuardDecision {

    /** Optional structured payload — rationale, scores, matched patterns, etc. May be null. */
    JsonNode info();

    /** Allow execution to continue. */
    record Allow(JsonNode info) implements GuardDecision {}

    /** Halt the run with a human-readable reason. */
    record Block(String reason, JsonNode info) implements GuardDecision {}

    static Allow allow() {
        return new Allow(null);
    }

    static Allow allow(JsonNode info) {
        return new Allow(info);
    }

    static Block block(String reason) {
        return new Block(reason, null);
    }

    static Block block(String reason, JsonNode info) {
        return new Block(reason, info);
    }
}
