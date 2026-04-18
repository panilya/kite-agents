package io.kite.internal.runtime;

/**
 * Mutable staging record for a tool call that's being assembled from a streaming model
 * response. Providers emit {@code ToolCallStart} (optionally followed by incremental
 * {@code ToolCallDelta} fragments) and a final {@code ToolCallComplete}; this class
 * accumulates until {@link TurnAccumulator#finalToolCalls()} converts it into an
 * immutable {@link io.kite.model.Message.ToolCallRef}.
 */
final class PendingCall {
    String id;
    String name;
    final StringBuilder args = new StringBuilder(128);
    boolean complete;
}
