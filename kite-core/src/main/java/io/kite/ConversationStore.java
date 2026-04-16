package io.kite;

import io.kite.model.Message;

import java.util.List;

/**
 * Pluggable history store. Implementations must be thread-safe — a single conversation ID may be
 * read and written concurrently by multiple handlers. The default implementation is
 * in-memory; future modules will add JDBC and Redis backends.
 *
 * <p><b>Persistence policy</b> (enforced by {@code Runner}): history is saved on
 * {@link io.kite.Status#OK OK} and {@link io.kite.Status#MAX_TURNS MAX_TURNS} outcomes — both
 * already burned tokens, so the user can resume / inspect them. {@link io.kite.Status#BLOCKED
 * BLOCKED} outcomes are <em>not</em> persisted: a guard refusal should not poison subsequent
 * turns on the same conversation id.
 */
public interface ConversationStore {

    /** Return the (immutable) history for a conversation, or an empty list if unknown. */
    List<Message> load(String conversationId);

    /** Atomically replace the history for a conversation. */
    void save(String conversationId, List<Message> updated);
}
