package io.kite.tracing;

import io.kite.model.ChatRequest;
import io.kite.model.ChatResponse;
import io.kite.model.Usage;

import java.time.Duration;
import java.time.Instant;

public sealed interface TraceEvent
        permits TraceEvent.LlmRequest,
                TraceEvent.LlmResponse,
                TraceEvent.ToolCall,
                TraceEvent.ToolResult,
                TraceEvent.Transfer,
                TraceEvent.GuardCheck,
                TraceEvent.Error {

    Instant timestamp();
    String agent();

    record LlmRequest(Instant timestamp, String agent, ChatRequest request) implements TraceEvent {}

    record LlmResponse(Instant timestamp, String agent, ChatResponse response, Duration elapsed, Usage usage) implements TraceEvent {}

    record ToolCall(Instant timestamp, String agent, String toolName, String argsJson) implements TraceEvent {}

    record ToolResult(Instant timestamp, String agent, String toolName, String resultJson, Duration elapsed) implements TraceEvent {}

    record Transfer(Instant timestamp, String agent, String to) implements TraceEvent {}

    record GuardCheck(Instant timestamp, String agent, String guard, String phase, boolean passed, String message) implements TraceEvent {}

    record Error(Instant timestamp, String agent, String message, Throwable cause) implements TraceEvent {}
}
