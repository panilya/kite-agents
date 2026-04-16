package io.kite;

import io.kite.model.Message;

import java.util.List;

/**
 * Pluggable history store. Implementations must be thread-safe — a single conversation ID may be
 * read and written concurrently by multiple handlers. The default implementation is
 * in-memory; future modules will add JDBC and Redis backends.
 */
public interface ConversationStore {

    /** Return the (immutable) history for a conversation, or an empty list if unknown. */
    List<Message> load(String conversationId);

    /** Atomically replace the history for a conversation. */
    void save(String conversationId, List<Message> updated);
}
