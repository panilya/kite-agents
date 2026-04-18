package io.kite.guards;

import io.kite.model.Message;

import java.util.List;

/**
 * Input to an {@link OutputGuard}'s check function. {@link #history()} holds everything the
 * LLM was given (prior turns + new user turn + this turn's tool calls and results) and does
 * <em>not</em> include the pending assistant reply — that is carried separately in
 * {@link #generatedResponse()}.
 */
public record OutputGuardInput<T>(
        List<Message> history,
        String generatedResponse,
        T context
) implements GuardInput<T> {

    public OutputGuardInput {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
