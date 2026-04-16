package io.kite.model;

/**
 * A single event from a streaming model call. Providers decode their wire format
 * (SSE events) into a sequence of these chunks.
 */
public sealed interface ChatChunk
        permits ChatChunk.TextDelta,
                ChatChunk.ToolCallStart,
                ChatChunk.ToolCallDelta,
                ChatChunk.ToolCallComplete,
                ChatChunk.Done,
                ChatChunk.Error {

    record TextDelta(String text) implements ChatChunk {}

    record ToolCallStart(int index, String id, String name) implements ChatChunk {}

    record ToolCallDelta(int index, String argsFragment) implements ChatChunk {}

    record ToolCallComplete(int index, String id, String name, String argsJson) implements ChatChunk {}

    record Done(Usage usage, String stopReason) implements ChatChunk {}

    record Error(String message, Throwable cause) implements ChatChunk {}
}
