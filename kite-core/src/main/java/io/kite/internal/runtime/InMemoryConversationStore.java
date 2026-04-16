package io.kite.internal.runtime;

import io.kite.ConversationStore;
import io.kite.model.Message;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link ConversationStore}: keeps all conversations in a process-local
 * {@link ConcurrentHashMap}. Readers receive immutable snapshots; writers atomically replace
 * the map entry.
 */
public final class InMemoryConversationStore implements ConversationStore {

    private final ConcurrentHashMap<String, List<Message>> map = new ConcurrentHashMap<>();

    @Override
    public List<Message> load(String conversationId) {
        if (conversationId == null) return List.of();
        return map.getOrDefault(conversationId, List.of());
    }

    @Override
    public void save(String conversationId, List<Message> updated) {
        if (conversationId == null) return;
        map.put(conversationId, List.copyOf(updated));
    }
}
