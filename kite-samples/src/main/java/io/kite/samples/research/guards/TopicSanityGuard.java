package io.kite.samples.research.guards;

import io.kite.guards.Guard;
import io.kite.guards.GuardDecision;
import io.kite.guards.InputGuard;

import java.util.Map;

/**
 * Input guard: rejects empty or trivially short research requests before any LLM tokens are
 * spent. Demonstrates the blocking path that produces a {@code Status.BLOCKED} reply with
 * zero token cost.
 */
public final class TopicSanityGuard {

    private TopicSanityGuard() {}

    public static <T> InputGuard<T> create() {
        return Guard.<T>input("topic-sanity")
                .blocking()
                .check(in -> {
                    var text = in.userText();
                    if (text == null || text.isBlank()) {
                        return GuardDecision.block(Map.of("message", "Please provide a research question. An empty query can't be researched."));
                    }
                    String trimmed = text.trim();
                    if (trimmed.length() < 6) {
                        return GuardDecision.block(Map.of("message", "Question is too short to research (got '" + trimmed
                                + "'). Please describe the topic in a full sentence."));
                    }
                    if (!trimmed.contains(" ")) {
                        return GuardDecision.block(Map.of("message", "Single-word queries aren't useful for deep research. "
                                + "Please phrase '" + trimmed + "' as a specific question."));
                    }
                    return GuardDecision.allow();
                });
    }
}
