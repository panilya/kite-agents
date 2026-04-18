package io.kite.internal.runtime;

import io.kite.model.Message;
import io.kite.model.Usage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the pieces of a streamed LLM turn — text deltas, tool-call fragments, and the final
 * {@link Usage} — so a synthetic {@link io.kite.model.ChatResponse} can be assembled once the
 * stream ends. One instance per turn; not thread-safe (chunks arrive serially from the provider).
 */
final class TurnAccumulator {

    final StringBuilder text = new StringBuilder(256);
    private final Map<Integer, PendingCall> toolCalls = new LinkedHashMap<>();
    Usage usage;

    PendingCall pending(int index) {
        return toolCalls.computeIfAbsent(index, k -> new PendingCall());
    }

    List<Message.ToolCallRef> finalToolCalls() {
        if (toolCalls.isEmpty()) return List.of();
        List<Message.ToolCallRef> out = new ArrayList<>(toolCalls.size());
        for (var pc : toolCalls.values()) {
            if (pc.name == null) continue;
            String id = pc.id == null ? "call_" + out.size() : pc.id;
            out.add(new Message.ToolCallRef(id, pc.name, pc.args.toString()));
        }
        return out;
    }
}
