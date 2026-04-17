package io.kite.samples.research;

import java.time.Duration;

/**
 * Per-run configuration threaded through every agent in the research pipeline via Kite's typed
 * context. Demonstrates {@code Agent.builder(ResearchContext.class)} and dynamic instructions
 * that reference the context ({@code .instructions(ctx -> ...)}).
 */
public record ResearchContext(
        String userId,
        int maxSubtopics,
        Duration totalBudget) {

    public static ResearchContext defaults(String userId) {
        return new ResearchContext(userId, 3, Duration.ofMinutes(3));
    }
}
