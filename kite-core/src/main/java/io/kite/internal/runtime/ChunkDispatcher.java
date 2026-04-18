package io.kite.internal.runtime;

import io.kite.Event;
import io.kite.model.ChatChunk;

/**
 * Routes a single {@link ChatChunk} into a {@link TurnAccumulator} and, for user-visible
 * text deltas and errors, emits the corresponding {@link Event} on the provided
 * {@link RunEmitter}. Tool-call fragments are silent until the complete batch is known —
 * {@code Event.ToolCall} is fired later from the main loop.
 */
final class ChunkDispatcher {

    private ChunkDispatcher() {}

    static void dispatch(ChatChunk chunk, String agentName, TurnAccumulator accum, RunEmitter emitter) {
        switch (chunk) {
            case ChatChunk.TextDelta d -> {
                accum.text.append(d.text());
                emitter.delta(agentName, d.text());
            }
            case ChatChunk.ToolCallStart s -> {
                PendingCall pc = accum.pending(s.index());
                pc.id = s.id();
                pc.name = s.name();
            }
            case ChatChunk.ToolCallDelta d -> accum.pending(d.index()).args.append(d.argsFragment());
            case ChatChunk.ToolCallComplete c -> {
                PendingCall pc = accum.pending(c.index());
                pc.id = c.id();
                pc.name = c.name();
                pc.args.setLength(0);
                pc.args.append(c.argsJson());
                pc.complete = true;
            }
            case ChatChunk.Done d -> accum.usage = d.usage();
            case ChatChunk.Error err -> emitter.error(
                    agentName,
                    err.cause() != null ? err.cause() : new RuntimeException(err.message()));
        }
    }
}
