package io.kite;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outcome of a guard check. A pass means "allow execution to continue"; a block means "stop and
 * surface this message to the caller." Guards never throw; blocking is expected control flow.
 */
public record GuardResult(
        boolean passing,
        String guard,        // name of the guard that produced this result (filled by executor)
        Phase phase,         // INPUT or OUTPUT (filled by executor)
        String message,      // human-readable block reason, or null when passing
        JsonNode info,       // optional structured data from agent-based guards
        String output        // for output guards: the original response text (for audit), null otherwise
) {

    public enum Phase { INPUT, OUTPUT }

    public static GuardResult pass() {
        return new GuardResult(true, null, null, null, null, null);
    }

    public static GuardResult block(String message) {
        return new GuardResult(false, null, null, message, null, null);
    }

    public static GuardResult block(String message, JsonNode info) {
        return new GuardResult(false, null, null, message, info, null);
    }

    public boolean blocked() {
        return !passing;
    }

    // Internal — used by GuardExecutor to attach metadata once it has it.
    public GuardResult withMetadata(String name, Phase p, String originalOutput) {
        return new GuardResult(passing, name, p, message, info, originalOutput);
    }
}
