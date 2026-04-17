package io.kite.samples.research.guards;

import io.kite.Guard;

/**
 * Input guard: rejects empty or trivially short research requests before any LLM tokens are
 * spent. Demonstrates the {@link Guard.Mode#BLOCKING} path that produces a
 * {@code Status.BLOCKED} reply with zero token cost.
 */
public final class TopicSanityGuard {

    private TopicSanityGuard() {}

    public static <T> Guard<T> create() {
        return Guard.<T>inputTyped("topic-sanity")
                .blocking()
                .check(input -> {
                    if (input == null || input.isBlank()) {
                        return Guard.block("Please provide a research question. An empty query can't be researched.");
                    }
                    String trimmed = input.trim();
                    if (trimmed.length() < 6) {
                        return Guard.block("Question is too short to research (got '" + trimmed
                                + "'). Please describe the topic in a full sentence.");
                    }
                    if (!trimmed.contains(" ")) {
                        return Guard.block("Single-word queries aren't useful for deep research. "
                                + "Please phrase '" + trimmed + "' as a specific question.");
                    }
                    return Guard.pass();
                });
    }
}
