package io.kite.internal.runtime;

import io.kite.Event;
import io.kite.Reply;
import io.kite.model.ChatRequest;
import io.kite.model.ChatResponse;
import io.kite.model.Message;
import io.kite.tracing.TraceContext;
import io.kite.tracing.TraceEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Unified sink for everything {@link Runner} wants to observe about a run — trace events and
 * user-facing {@link Event}s alike. Non-streaming runs only trace; streaming runs additionally
 * emit {@link Event}s to the caller's {@code Consumer<Event>} and record a transcript for
 * {@link Reply#events()}. By funnelling both through one interface the core loop has exactly
 * one emit call per observable milestone.
 *
 * <p>{@link #beginFirstTurnGating(boolean)}, {@link #firstTurnCommit()}, and
 * {@link #firstTurnBlock()} are hooks for the first-turn guard race. Non-streaming impls are
 * no-ops; {@link StreamingEmitter} uses them to buffer or gate streamed deltas until the
 * guards resolve.
 */
sealed interface RunEmitter permits RunEmitter.TraceOnlyEmitter, RunEmitter.StreamingEmitter {

    void llmRequest(String agent, ChatRequest req);

    void llmResponse(String agent, ChatResponse resp, Duration elapsed);

    void delta(String agent, String text);

    void toolCall(String agent, Message.ToolCallRef call);

    void toolResult(String agent, String name, String resultJson, Duration elapsed);

    void transfer(String from, String to);

    void blocked(String agent, String guard, String message);

    void error(String agent, Throwable cause);

    void done(String agent, Reply reply);

    /** Snapshot of events emitted so far (empty for non-streaming). */
    List<Event> transcript();

    /* -------- first-turn race gating (no-op for non-streaming) -------- */

    void beginFirstTurnGating(boolean buffer);

    void firstTurnCommit();

    void firstTurnBlock();

    static RunEmitter traceOnly(RunnerCore core, TraceContext tctx) {
        return new TraceOnlyEmitter(core, tctx);
    }

    static StreamingEmitter streaming(RunnerCore core, TraceContext tctx, java.util.function.Consumer<Event> downstream) {
        return new StreamingEmitter(core, tctx, downstream);
    }

    /* ==================================================================================== */

    /** Traces only — every Event method is a no-op. Used by the non-streaming run path. */
    final class TraceOnlyEmitter implements RunEmitter {
        private final RunnerCore core;
        private final TraceContext tctx;

        TraceOnlyEmitter(RunnerCore core, TraceContext tctx) {
            this.core = core;
            this.tctx = tctx;
        }

        @Override public void llmRequest(String agent, ChatRequest req) {
            core.trace(tctx, new TraceEvent.LlmRequest(Instant.now(), agent, req));
        }

        @Override public void llmResponse(String agent, ChatResponse resp, Duration elapsed) {
            core.trace(tctx, new TraceEvent.LlmResponse(Instant.now(), agent, resp, elapsed, resp.usage()));
        }

        @Override public void delta(String agent, String text) {}

        @Override public void toolCall(String agent, Message.ToolCallRef call) {
            core.trace(tctx, new TraceEvent.ToolCall(Instant.now(), agent, call.name(), call.argsJson()));
        }

        @Override public void toolResult(String agent, String name, String resultJson, Duration elapsed) {
            core.trace(tctx, new TraceEvent.ToolResult(Instant.now(), agent, name, resultJson, elapsed));
        }

        @Override public void transfer(String from, String to) {
            core.trace(tctx, new TraceEvent.Transfer(Instant.now(), from, to));
        }

        @Override public void blocked(String agent, String guard, String message) {}

        @Override public void error(String agent, Throwable cause) {
            core.trace(tctx, new TraceEvent.Error(Instant.now(), agent, cause.getMessage(), cause));
        }

        @Override public void done(String agent, Reply reply) {}

        @Override public List<Event> transcript() { return List.of(); }

        @Override public void beginFirstTurnGating(boolean buffer) {}

        @Override public void firstTurnCommit() {}

        @Override public void firstTurnBlock() {}
    }

    /* ==================================================================================== */

    /**
     * Traces <em>and</em> emits {@link Event}s to the caller's consumer. During the first-turn
     * guard race, streamed deltas can be buffered (BUFFER mode) or gated (PASSTHROUGH mode)
     * via {@link #beginFirstTurnGating(boolean)}; the sink then either flushes on
     * {@link #firstTurnCommit()} or discards on {@link #firstTurnBlock()}.
     *
     * <p>All other events (tool calls, transfers, etc.) are emitted only <em>after</em> the
     * first-turn race resolves, so they never require buffering — they just go through the
     * currently active sink, which by that point is straight record-and-forward.
     */
    final class StreamingEmitter implements RunEmitter {
        private final RunnerCore core;
        private final TraceContext tctx;
        private final java.util.ArrayList<Event> transcript = new java.util.ArrayList<>(64);
        private final java.util.function.Consumer<Event> recordAndForward;

        private java.util.function.Consumer<Event> active;
        private StreamBuffer firstTurnBuffer;
        private java.util.concurrent.atomic.AtomicBoolean passthroughGate;

        StreamingEmitter(RunnerCore core, TraceContext tctx, java.util.function.Consumer<Event> downstream) {
            this.core = core;
            this.tctx = tctx;
            this.recordAndForward = e -> {
                transcript.add(e);
                downstream.accept(e);
            };
            this.active = recordAndForward;
        }

        @Override public void llmRequest(String agent, ChatRequest req) {
            core.trace(tctx, new TraceEvent.LlmRequest(Instant.now(), agent, req));
        }

        @Override public void llmResponse(String agent, ChatResponse resp, Duration elapsed) {
            core.trace(tctx, new TraceEvent.LlmResponse(Instant.now(), agent, resp, elapsed, resp.usage()));
        }

        @Override public void delta(String agent, String text) {
            active.accept(new Event.Delta(agent, text));
        }

        @Override public void toolCall(String agent, Message.ToolCallRef call) {
            active.accept(new Event.ToolCall(agent, call.name(), call.argsJson()));
            core.trace(tctx, new TraceEvent.ToolCall(Instant.now(), agent, call.name(), call.argsJson()));
        }

        @Override public void toolResult(String agent, String name, String resultJson, Duration elapsed) {
            active.accept(new Event.ToolResult(agent, name, resultJson, elapsed));
            core.trace(tctx, new TraceEvent.ToolResult(Instant.now(), agent, name, resultJson, elapsed));
        }

        @Override public void transfer(String from, String to) {
            active.accept(new Event.Transfer(from, to));
            core.trace(tctx, new TraceEvent.Transfer(Instant.now(), from, to));
        }

        @Override public void blocked(String agent, String guard, String message) {
            active.accept(new Event.Blocked(agent, guard, message));
        }

        @Override public void error(String agent, Throwable cause) {
            active.accept(new Event.Error(agent, cause));
            core.trace(tctx, new TraceEvent.Error(Instant.now(), agent, cause.getMessage(), cause));
        }

        @Override public void done(String agent, Reply reply) {
            active.accept(new Event.Done(agent, reply));
        }

        @Override public List<Event> transcript() { return List.copyOf(transcript); }

        @Override public void beginFirstTurnGating(boolean buffer) {
            if (buffer) {
                firstTurnBuffer = new StreamBuffer(recordAndForward);
                active = firstTurnBuffer::accept;
            } else {
                passthroughGate = new java.util.concurrent.atomic.AtomicBoolean(true);
                var gate = passthroughGate;
                active = e -> { if (gate.get()) recordAndForward.accept(e); };
            }
        }

        @Override public void firstTurnCommit() {
            if (firstTurnBuffer != null) {
                firstTurnBuffer.flushAndSeal(recordAndForward);
                firstTurnBuffer = null;
            }
            passthroughGate = null;
            active = recordAndForward;
        }

        @Override public void firstTurnBlock() {
            // Seal the gating so any late-arriving chunk deltas from the cancelled stream get
            // dropped (BUFFER) or gated (PASSTHROUGH). Subsequent events emitted by the main
            // loop — Event.Blocked, Event.Done — still need to reach downstream, so active
            // reverts to the straight record-and-forward sink.
            if (firstTurnBuffer != null) {
                firstTurnBuffer.discardAndSeal();
                firstTurnBuffer = null;
            }
            if (passthroughGate != null) passthroughGate.set(false);
            passthroughGate = null;
            active = recordAndForward;
        }
    }
}
