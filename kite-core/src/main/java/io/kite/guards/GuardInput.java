package io.kite.guards;

import io.kite.model.Message;

import java.util.List;

/**
 * Sealed input to a guard's check function, split by phase. Each phase has its own record
 * with the data available at that point in the run.
 *
 * <p>Runtime invariants (held by the executor and relied on by guard authors):
 * <ul>
 *   <li>{@link InputGuardInput#history()} ends with a {@link Message.User} — the new input.</li>
 *   <li>{@link OutputGuardInput#history()} ends with whatever preceded the LLM's final emission
 *       (Tool result, User turn, etc.). The pending assistant reply is carried separately in
 *       {@link OutputGuardInput#generatedResponse()} and is not yet persisted.</li>
 * </ul>
 */
public sealed interface GuardInput<T> permits InputGuardInput, OutputGuardInput {

    /** Conversation state the LLM saw at its emission point. Immutable. */
    List<Message> history();

    /** Typed agent context (null for {@code Void} agents). */
    T context();
}
