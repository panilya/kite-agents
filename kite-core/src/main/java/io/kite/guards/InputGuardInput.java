package io.kite.guards;

import io.kite.model.Message;

import java.util.List;

/**
 * Input to an {@link InputGuard}'s check function. {@link #history()} ends with the new
 * {@link Message.User} holding the incoming text — already appended in-memory, not yet
 * persisted.
 */
public record InputGuardInput<T>(
        List<Message> history,
        T context
) implements GuardInput<T> {

    public InputGuardInput {
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** Text of the user message that triggered this guard check. */
    public String userText() {
        return ((Message.User) history.getLast()).content();
    }
}
