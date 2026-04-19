package io.kite.guards;

import java.util.Map;

/**
 * The verdict a guard's check function produces. Sealed — a guard either {@link Allow}s
 * or {@link Block}s the run. Both variants carry an optional structured {@code info} payload
 * for audit, explainability, or observability dashboards. The framework makes no assumptions
 * about the contents of {@code info}; guards pick whatever keys suit their domain.
 */
public sealed interface GuardDecision {

    /**
     * Optional structured payload — rationale, scores, matched patterns, human-readable reason,
     * or whatever the guard chooses. Values should be JSON-serializable: primitives, strings,
     * booleans, lists, or nested maps. May be null.
     */
    Map<String, Object> info();

    /** Allow execution to continue. */
    record Allow(Map<String, Object> info) implements GuardDecision {}

    /** Halt the run. */
    record Block(Map<String, Object> info) implements GuardDecision {}

    static Allow allow() {
        return new Allow(null);
    }

    static Allow allow(Map<String, Object> info) {
        return new Allow(info);
    }

    static Block block() {
        return new Block(null);
    }

    static Block block(Map<String, Object> info) {
        return new Block(info);
    }
}
