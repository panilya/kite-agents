package io.kite.model;

import java.util.List;

/**
 * Provider-neutral non-streaming response. Producers fill in whatever fields they have;
 * {@code content} may be empty when the model only emitted tool calls.
 */
public record ChatResponse(
        String content,
        List<Message.ToolCallRef> toolCalls,
        Usage usage,
        String stopReason,
        String model,
        String id) {

    public ChatResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? Usage.ZERO : usage;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
