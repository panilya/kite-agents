package io.kite.tracing;

import java.time.Instant;
import java.util.Map;

public record TraceContext(
        String traceId,
        String conversationId,
        String rootAgent,
        Instant startTime,
        Map<String, String> metadata) {

    public TraceContext {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
