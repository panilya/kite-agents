package io.kite.internal.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.kite.Agent;
import io.kite.AgentBuilder;
import io.kite.AgentRef;
import io.kite.Event;
import io.kite.GuardResult;
import io.kite.Reply;
import io.kite.model.ChatChunk;
import io.kite.model.ChatRequest;
import io.kite.model.ChatResponse;
import io.kite.model.Message;
import io.kite.model.ModelProvider;
import io.kite.model.Usage;
import io.kite.tracing.TraceContext;
import io.kite.tracing.TraceEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The two execution loops — {@link #runLoop} for non-streaming and {@link #streamLoop} for
 * streaming. Both delegate shared state-machine logic to {@link RunnerCore}; neither is
 * implemented in terms of the other.
 *
 * <p>Delegation (Agent-as-Tool) reuses the non-streaming loop body via {@link #runDelegate},
 * which executes under the parent's {@link TraceContext} so the delegate's internal events
 * appear inline under the same trace id.
 */
public final class Runner {

    private final RunnerCore core;

    public Runner(RunnerCore core) {
        this.core = core;
    }

    /* ============================ Non-streaming loop ============================ */

    public <T> Reply runLoop(Agent<T> rootAgent, String input, String conversationId, T ctx) {
        TraceContext tctx = core.startTrace(rootAgent, conversationId);
        try {
            return executeLoop(rootAgent, input, conversationId, ctx, tctx);
        } finally {
            core.endTrace(tctx);
        }
    }

    /**
     * Run a delegated sub-agent under the parent's trace context. History is fresh (no
     * conversation store read or write), so the delegate sees only the {@code input} as a single
     * user message. The unchecked cast is safe because {@link AgentBuilder#build()} enforces that
     * every DELEGATE tool's target has a context type compatible with the parent's.
     */
    private Reply runDelegate(Agent<?> delegate, String input, Object ctx, TraceContext parentTctx) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Reply reply = executeLoop((Agent) delegate, input, null, ctx, parentTctx);
        return reply;
    }

    private <T> Reply executeLoop(Agent<T> rootAgent, String input, String conversationId, T ctx, TraceContext tctx) {
        GuardResult blocking = core.runInputBlocking(rootAgent, ctx, input);
        if (blocking.blocked()) return Reply.blocked(blocking, refOf(rootAgent), Usage.ZERO, tctx.traceId(), List.of());
        GuardResult parallel = core.runInputParallel(rootAgent, ctx, input);
        if (parallel.blocked()) return Reply.blocked(parallel, refOf(rootAgent), Usage.ZERO, tctx.traceId(), List.of());

        RunnerCore.DelegateRunner delegateRunner = (d, i, c) -> runDelegate(d, i, c, tctx);
        List<Message> history = core.loadHistory(conversationId);
        history.add(new Message.User(input));

        Agent<T> current = rootAgent;
        Usage accumulatedUsage = Usage.ZERO;
        String lastText = "";
        JsonNode lastStructured = null;

        int maxTurns = core.effectiveMaxTurns(rootAgent);
        int turns = 0;
        boolean toolChoiceSatisfied = false;
        while (turns++ < maxTurns) {
            ChatRequest req = core.buildRequest(current, Collections.unmodifiableList(history), ctx, false, toolChoiceSatisfied);
            core.trace(tctx, new TraceEvent.LlmRequest(Instant.now(), current.name(), req));

            ModelProvider provider = core.pickProvider(current.model());
            Instant start = Instant.now();
            ChatResponse resp = provider.chat(req);
            Duration elapsed = Duration.between(start, Instant.now());
            core.trace(tctx, new TraceEvent.LlmResponse(Instant.now(), current.name(), resp, elapsed, resp.usage()));
            accumulatedUsage = accumulatedUsage.plus(resp.usage());

            history.add(new Message.Assistant(resp.content(), resp.toolCalls()));
            lastText = resp.content() == null ? "" : resp.content();

            if (!resp.hasToolCalls()) {
                // Terminal response.
                GuardResult after = core.runOutput(current, ctx, lastText);
                if (after.blocked()) {
                    return Reply.blocked(after, refOf(current), accumulatedUsage, tctx.traceId(), List.of());
                }
                if (current.outputType() != null && !lastText.isEmpty()) {
                    lastStructured = core.codec().readTree(lastText);
                }
                core.saveHistory(conversationId, history);
                return Reply.ok(lastText, refOf(current), lastStructured, accumulatedUsage, tctx.traceId(), List.of());
            }

            // Handle tool calls — first check for routing.
            Agent<T> routeTarget = null;
            for (var call : resp.toolCalls()) {
                Agent<T> t = core.resolveRoute(current, call.name());
                if (t != null) { routeTarget = t; break; }
            }
            if (routeTarget != null) {
                core.trace(tctx, new TraceEvent.Transfer(Instant.now(), current.name(), routeTarget.name()));
                core.appendRoutedToolOutputs(history, resp.toolCalls(), routeTarget);
                current = routeTarget;
                toolChoiceSatisfied = false;
                continue;
            }

            // Execute non-route tool calls, append their results to history.
            for (var call : resp.toolCalls()) {
                core.trace(tctx, new TraceEvent.ToolCall(Instant.now(), current.name(), call.name(), call.argsJson()));
                Instant toolStart = Instant.now();
                RunnerCore.ToolCallOutcome outcome;
                try {
                    outcome = core.executeToolCall(current, call.name(), call.argsJson(), ctx, delegateRunner);
                } catch (Exception e) {
                    outcome = new RunnerCore.ToolCallOutcome(
                            core.codec().writeValueAsString(
                                    Map.of("error", e.getMessage() == null ? "tool failed" : e.getMessage())),
                            Usage.ZERO);
                }
                String result = outcome.resultJson();
                accumulatedUsage = accumulatedUsage.plus(outcome.usage());
                Duration toolElapsed = Duration.between(toolStart, Instant.now());
                core.trace(tctx, new TraceEvent.ToolResult(Instant.now(), current.name(), call.name(), result, toolElapsed));
                history.add(new Message.Tool(call.id(), call.name(), result));
            }
            toolChoiceSatisfied = true;
        }

        return Reply.maxTurns(lastText, refOf(current), accumulatedUsage, tctx.traceId(), List.of());
    }

    /* ============================== Streaming loop ============================== */

    public <T> void streamLoop(Agent<T> rootAgent, String input, String conversationId, T ctx, Consumer<Event> downstream) {
        TraceContext tctx = core.startTrace(rootAgent, conversationId);
        RunnerCore.DelegateRunner delegateRunner = (d, i, c) -> runDelegate(d, i, c, tctx);
        List<Event> transcript = new ArrayList<>(128);
        Consumer<Event> out = e -> {
            transcript.add(e);
            downstream.accept(e);
        };

        try {
            GuardResult blocking = core.runInputBlocking(rootAgent, ctx, input);
            if (blocking.blocked()) {
                var blockedEvent = new Event.Blocked(rootAgent.name(), blocking.guard(), blocking.message());
                out.accept(blockedEvent);
                var reply = Reply.blocked(blocking, refOf(rootAgent), Usage.ZERO, tctx.traceId(), List.copyOf(transcript));
                out.accept(new Event.Done(rootAgent.name(), reply));
                return;
            }
            GuardResult parallel = core.runInputParallel(rootAgent, ctx, input);
            if (parallel.blocked()) {
                out.accept(new Event.Blocked(rootAgent.name(), parallel.guard(), parallel.message()));
                var reply = Reply.blocked(parallel, refOf(rootAgent), Usage.ZERO, tctx.traceId(), List.copyOf(transcript));
                out.accept(new Event.Done(rootAgent.name(), reply));
                return;
            }

            List<Message> history = core.loadHistory(conversationId);
            history.add(new Message.User(input));

            Agent<T> current = rootAgent;
            Usage accumulatedUsage = Usage.ZERO;
            String lastText = "";
            JsonNode lastStructured = null;

            int maxTurns = core.effectiveMaxTurns(rootAgent);
            int turns = 0;
            boolean toolChoiceSatisfied = false;
            outer:
            while (turns++ < maxTurns) {
                ChatRequest req = core.buildRequest(current, Collections.unmodifiableList(history), ctx, true, toolChoiceSatisfied);
                core.trace(tctx, new TraceEvent.LlmRequest(Instant.now(), current.name(), req));
                ModelProvider provider = core.pickProvider(current.model());

                var turnAccum = new TurnAccumulator();
                String currentAgentName = current.name();
                Instant start = Instant.now();
                provider.chatStream(req, chunk -> dispatchChunk(chunk, currentAgentName, turnAccum, out));
                Duration elapsed = Duration.between(start, Instant.now());

                var fakeResp = new ChatResponse(turnAccum.text.toString(), turnAccum.finalToolCalls(),
                        turnAccum.usage == null ? Usage.ZERO : turnAccum.usage,
                        "stop", current.model(), null);
                core.trace(tctx, new TraceEvent.LlmResponse(Instant.now(), current.name(), fakeResp, elapsed, fakeResp.usage()));
                accumulatedUsage = accumulatedUsage.plus(fakeResp.usage());

                history.add(new Message.Assistant(fakeResp.content(), fakeResp.toolCalls()));
                lastText = fakeResp.content();

                if (!fakeResp.hasToolCalls()) {
                    GuardResult after = core.runOutput(current, ctx, lastText);
                    if (after.blocked()) {
                        out.accept(new Event.Blocked(current.name(), after.guard(), after.message()));
                        var reply = Reply.blocked(after, refOf(current), accumulatedUsage, tctx.traceId(), List.copyOf(transcript));
                        out.accept(new Event.Done(current.name(), reply));
                        return;
                    }
                    if (current.outputType() != null && !lastText.isEmpty()) {
                        lastStructured = core.codec().readTree(lastText);
                    }
                    core.saveHistory(conversationId, history);
                    var reply = Reply.ok(lastText, refOf(current), lastStructured, accumulatedUsage, tctx.traceId(), List.copyOf(transcript));
                    out.accept(new Event.Done(current.name(), reply));
                    return;
                }

                // Tool calls — route first.
                Agent<T> routeTarget = null;
                for (var call : fakeResp.toolCalls()) {
                    Agent<T> t = core.resolveRoute(current, call.name());
                    if (t != null) { routeTarget = t; break; }
                }
                if (routeTarget != null) {
                    out.accept(new Event.Transfer(current.name(), routeTarget.name()));
                    core.trace(tctx, new TraceEvent.Transfer(Instant.now(), current.name(), routeTarget.name()));
                    core.appendRoutedToolOutputs(history, fakeResp.toolCalls(), routeTarget);
                    current = routeTarget;
                    toolChoiceSatisfied = false;
                    continue outer;
                }

                for (var call : fakeResp.toolCalls()) {
                    out.accept(new Event.ToolCall(current.name(), call.name(), call.argsJson()));
                    core.trace(tctx, new TraceEvent.ToolCall(Instant.now(), current.name(), call.name(), call.argsJson()));
                    Instant toolStart = Instant.now();
                    RunnerCore.ToolCallOutcome outcome;
                    try {
                        outcome = core.executeToolCall(current, call.name(), call.argsJson(), ctx, delegateRunner);
                    } catch (Exception e) {
                        outcome = new RunnerCore.ToolCallOutcome(
                                core.codec().writeValueAsString(
                                        Map.of("error", e.getMessage() == null ? "tool failed" : e.getMessage())),
                                Usage.ZERO);
                    }
                    String result = outcome.resultJson();
                    accumulatedUsage = accumulatedUsage.plus(outcome.usage());
                    Duration toolElapsed = Duration.between(toolStart, Instant.now());
                    out.accept(new Event.ToolResult(current.name(), call.name(), result, toolElapsed));
                    core.trace(tctx, new TraceEvent.ToolResult(Instant.now(), current.name(), call.name(), result, toolElapsed));
                    history.add(new Message.Tool(call.id(), call.name(), result));
                }
                toolChoiceSatisfied = true;
            }

            var reply = Reply.maxTurns(lastText, refOf(current), accumulatedUsage, tctx.traceId(), List.copyOf(transcript));
            out.accept(new Event.Done(current.name(), reply));
        } catch (RuntimeException e) {
            out.accept(new Event.Error(rootAgent.name(), e));
            throw e;
        } finally {
            core.endTrace(tctx);
        }
    }

    private static void dispatchChunk(ChatChunk chunk, String agentName, TurnAccumulator accum, Consumer<Event> out) {
        switch (chunk) {
            case ChatChunk.TextDelta d -> {
                accum.text.append(d.text());
                out.accept(new Event.Delta(agentName, d.text()));
            }
            case ChatChunk.ToolCallStart s -> {
                accum.toolCalls.computeIfAbsent(s.index(), k -> new PendingCall()).id = s.id();
                accum.toolCalls.get(s.index()).name = s.name();
            }
            case ChatChunk.ToolCallDelta d -> {
                accum.toolCalls.computeIfAbsent(d.index(), k -> new PendingCall()).args.append(d.argsFragment());
            }
            case ChatChunk.ToolCallComplete c -> {
                var pc = accum.toolCalls.computeIfAbsent(c.index(), k -> new PendingCall());
                pc.id = c.id();
                pc.name = c.name();
                pc.args.setLength(0);
                pc.args.append(c.argsJson());
                pc.complete = true;
            }
            case ChatChunk.Done d -> accum.usage = d.usage();
            case ChatChunk.Error err -> out.accept(new Event.Error(agentName, err.cause() != null ? err.cause() : new RuntimeException(err.message())));
        }
    }

    private static AgentRef refOf(Agent<?> agent) {
        return new AgentRef(agent.name(), agent.model());
    }

    private static final class TurnAccumulator {
        final StringBuilder text = new StringBuilder(256);
        final Map<Integer, PendingCall> toolCalls = new LinkedHashMap<>();
        Usage usage;

        List<Message.ToolCallRef> finalToolCalls() {
            if (toolCalls.isEmpty()) return List.of();
            List<Message.ToolCallRef> out = new ArrayList<>(toolCalls.size());
            for (var pc : toolCalls.values()) {
                if (pc.name == null) continue;
                out.add(new Message.ToolCallRef(
                        pc.id == null ? "call_" + out.size() : pc.id,
                        pc.name,
                        pc.args.toString()));
            }
            return out;
        }
    }

    private static final class PendingCall {
        String id;
        String name;
        final StringBuilder args = new StringBuilder(128);
        boolean complete;
    }
}
