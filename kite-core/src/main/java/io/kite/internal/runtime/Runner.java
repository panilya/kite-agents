package io.kite.internal.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.kite.Agent;
import io.kite.AgentBuilder;
import io.kite.AgentRef;
import io.kite.Event;
import io.kite.GuardResult;
import io.kite.Reply;
import io.kite.RunInterruptedException;
import io.kite.Tool;
import io.kite.ToolFailure;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The two execution loops — {@link #runLoop} for non-streaming and {@link #streamLoop} for
 * streaming. Both delegate shared state-machine logic to {@link RunnerCore}; neither is
 * implemented in terms of the other.
 *
 * <p>On the first turn, parallel input guards race the LLM call via
 * {@link ParallelGuardHandle} on the shared virtual-thread executor. Read-only tool calls
 * can also start in parallel with still-running guards — their results are thrown away
 * if any guard blocks. Subsequent turns (tool-follow-up, post-handoff) see guards already
 * resolved and proceed synchronously.
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
        GuardResult blocking = core.runInputBlocking(rootAgent, ctx, input, tctx);
        if (blocking.blocked()) return Reply.blocked(blocking, refOf(rootAgent), Usage.ZERO, tctx.traceId(), List.of());

        ParallelGuardHandle guardHandle = core.startInputParallel(rootAgent, ctx, input, tctx);

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
        boolean firstTurn = true;
        while (turns++ < maxTurns) {
            ChatRequest req = core.buildRequest(current, Collections.unmodifiableList(history), ctx, false, toolChoiceSatisfied);
            core.trace(tctx, new TraceEvent.LlmRequest(Instant.now(), current.name(), req));

            ModelProvider provider = core.pickProvider(current.model());

            ChatResponse resp;
            Duration elapsed;
            Map<String, SpeculativeOutcome> speculativeResults = Map.of();

            if (firstTurn) {
                FirstTurnOutcome outcome = raceFirstTurn(provider, req, guardHandle, current, ctx, delegateRunner);
                if (outcome instanceof FirstTurnOutcome.Blocked b) {
                    return Reply.blocked(b.guardResult(), refOf(rootAgent), Usage.ZERO, tctx.traceId(), List.of());
                }
                var c = (FirstTurnOutcome.Committed) outcome;
                resp = c.response();
                elapsed = c.elapsed();
                speculativeResults = c.speculatives();
                firstTurn = false;
            } else {
                Instant start = Instant.now();
                resp = provider.chat(req);
                elapsed = Duration.between(start, Instant.now());
            }

            core.trace(tctx, new TraceEvent.LlmResponse(Instant.now(), current.name(), resp, elapsed, resp.usage()));
            accumulatedUsage = accumulatedUsage.plus(resp.usage());

            history.add(new Message.Assistant(resp.content(), resp.toolCalls()));
            lastText = resp.content() == null ? "" : resp.content();

            if (!resp.hasToolCalls()) {
                // Terminal response.
                GuardResult after = core.runOutput(current, ctx, lastText, tctx);
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
                var outcomes = core.appendRoutedToolOutputs(history, resp.toolCalls(), routeTarget);
                for (var o : outcomes) {
                    if (!o.taken()) {
                        core.trace(tctx, new TraceEvent.ToolResult(
                                Instant.now(), current.name(), o.call().name(), o.resultJson(), Duration.ZERO));
                    }
                }
                current = routeTarget;
                toolChoiceSatisfied = false;
                continue;
            }

            // Execute non-route tool calls, append their results to history. Speculative (read-only)
            // results come from the map; everything else runs serially here.
            for (var call : resp.toolCalls()) {
                core.trace(tctx, new TraceEvent.ToolCall(Instant.now(), current.name(), call.name(), call.argsJson()));
                SpeculativeOutcome spec = speculativeResults.get(call.id());
                String result;
                Duration toolElapsed;
                if (spec != null) {
                    toolElapsed = spec.elapsed();
                    if (spec.failure() != null) {
                        core.trace(tctx, new TraceEvent.Error(Instant.now(), current.name(), spec.failure().getMessage(), spec.failure()));
                        result = errorPayload(spec.failure());
                    } else {
                        accumulatedUsage = accumulatedUsage.plus(spec.outcome().usage());
                        result = spec.outcome().resultJson();
                    }
                } else {
                    Instant toolStart = Instant.now();
                    RunnerCore.ToolCallOutcome outcome;
                    try {
                        outcome = core.executeToolCall(current, call.name(), call.argsJson(), ctx, delegateRunner);
                    } catch (ToolFailure f) {
                        core.trace(tctx, new TraceEvent.Error(Instant.now(), current.name(), f.getMessage(), f));
                        outcome = new RunnerCore.ToolCallOutcome(errorPayload(f), Usage.ZERO);
                    }
                    result = outcome.resultJson();
                    accumulatedUsage = accumulatedUsage.plus(outcome.usage());
                    toolElapsed = Duration.between(toolStart, Instant.now());
                }
                core.trace(tctx, new TraceEvent.ToolResult(Instant.now(), current.name(), call.name(), result, toolElapsed));
                history.add(new Message.Tool(call.id(), call.name(), result));
            }
            toolChoiceSatisfied = true;
        }

        core.saveHistory(conversationId, history);
        return Reply.maxTurns(lastText, refOf(current), accumulatedUsage, tctx.traceId(), List.of());
    }

    /* ========================== First-turn race orchestration ========================== */

    /**
     * Race parallel guards against the first-turn LLM call, and dispatch any read-only tool
     * calls the LLM returns in parallel with still-running guards. Results are committed only
     * if every guard passes; otherwise everything is discarded and a blocked outcome is returned.
     */
    private <T> FirstTurnOutcome raceFirstTurn(ModelProvider provider,
                                               ChatRequest req,
                                               ParallelGuardHandle guardHandle,
                                               Agent<T> current,
                                               T ctx,
                                               RunnerCore.DelegateRunner delegateRunner) {
        Instant llmStart = Instant.now();
        CompletableFuture<ChatResponse> llmFuture = CompletableFuture.supplyAsync(
                () -> provider.chat(req), core.vexec());

        Object winner;
        try {
            winner = CompletableFuture.anyOf(guardHandle.firstBlock(), llmFuture).join();
        } catch (CompletionException ce) {
            // LLM failed. A guard that was about to block would be a more informative outcome,
            // so wait for the guards before propagating the LLM error.
            GuardResult gr = guardHandle.completion().join();
            if (gr.blocked()) {
                guardHandle.cancelAll();
                return new FirstTurnOutcome.Blocked(gr);
            }
            Throwable cause = Throwables.unwrapCompletion(ce);
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }

        if (winner instanceof GuardResult gr && gr.blocked()) {
            llmFuture.cancel(true);
            guardHandle.cancelAll();
            return new FirstTurnOutcome.Blocked(gr);
        }

        ChatResponse resp = (ChatResponse) winner;
        var partition = partitionForSpeculation(resp.toolCalls(), current);
        var specFutures = dispatchSpeculatives(partition.readOnlyCalls(), current, ctx, delegateRunner);

        GuardResult guardResult = guardHandle.completion().join();
        if (guardResult.blocked()) {
            cancelAll(specFutures);
            return new FirstTurnOutcome.Blocked(guardResult);
        }

        Map<String, SpeculativeOutcome> results = collectSpeculatives(partition.readOnlyCalls(), specFutures);
        Duration elapsed = Duration.between(llmStart, Instant.now());
        return new FirstTurnOutcome.Committed(resp, elapsed, results);
    }

    /**
     * Single-pass partition of a tool-call batch into (has-route, read-only-calls). A route in
     * the batch wins globally — Kite transfers after the assistant message is appended — so we
     * don't speculate on anything when a route is present.
     */
    private <T> SpecPartition partitionForSpeculation(List<Message.ToolCallRef> calls, Agent<T> agent) {
        if (calls.isEmpty()) return SpecPartition.EMPTY;
        List<Message.ToolCallRef> readOnly = new ArrayList<>();
        for (var call : calls) {
            if (core.resolveRoute(agent, call.name()) != null) {
                return new SpecPartition(true, List.of());
            }
            Tool t = core.findTool(agent, call.name());
            if (t != null && t.readOnly()) readOnly.add(call);
        }
        return new SpecPartition(false, readOnly);
    }

    private <T> Map<String, CompletableFuture<SpeculativeOutcome>> dispatchSpeculatives(
            List<Message.ToolCallRef> readOnlyCalls,
            Agent<T> agent,
            T ctx,
            RunnerCore.DelegateRunner delegateRunner) {
        if (readOnlyCalls.isEmpty()) return Map.of();
        Map<String, CompletableFuture<SpeculativeOutcome>> futures = new HashMap<>(readOnlyCalls.size());
        for (var call : readOnlyCalls) {
            var f = CompletableFuture.supplyAsync(
                    () -> invokeSpeculatively(agent, call, ctx, delegateRunner),
                    core.vexec());
            futures.put(call.id(), f);
        }
        return futures;
    }

    private Map<String, SpeculativeOutcome> collectSpeculatives(
            List<Message.ToolCallRef> readOnlyCalls,
            Map<String, CompletableFuture<SpeculativeOutcome>> futures) {
        if (futures.isEmpty()) return Map.of();
        Map<String, SpeculativeOutcome> results = new HashMap<>(futures.size());
        for (var call : readOnlyCalls) {
            var future = futures.get(call.id());
            if (future == null) continue;
            try {
                results.put(call.id(), future.join());
            } catch (CancellationException | CompletionException ex) {
                // Only fires for unexpected exceptions — invokeSpeculatively catches ToolFailure
                // and RunInterruptedException itself, and we only cancel futures on block paths
                // (which bypass collection). Surface as a ThrownByTool so the LLM can still
                // recover on the follow-up turn.
                Throwable cause = Throwables.unwrapCompletion(ex);
                ToolFailure f = new ToolFailure.ThrownByTool(
                        call.name(),
                        "Tool '" + call.name() + "' failed: " + Throwables.describe(cause),
                        cause);
                results.put(call.id(), new SpeculativeOutcome(null, f, Duration.ZERO));
            }
        }
        return results;
    }

    private static void cancelAll(Map<String, CompletableFuture<SpeculativeOutcome>> futures) {
        for (var f : futures.values()) f.cancel(true);
    }

    private <T> SpeculativeOutcome invokeSpeculatively(Agent<T> agent,
                                                       Message.ToolCallRef call,
                                                       T ctx,
                                                       RunnerCore.DelegateRunner delegateRunner) {
        Instant start = Instant.now();
        try {
            RunnerCore.ToolCallOutcome out = core.executeToolCall(agent, call.name(), call.argsJson(), ctx, delegateRunner);
            return new SpeculativeOutcome(out, null, Duration.between(start, Instant.now()));
        } catch (ToolFailure f) {
            return new SpeculativeOutcome(null, f, Duration.between(start, Instant.now()));
        } catch (RunInterruptedException rie) {
            return new SpeculativeOutcome(null, null, Duration.between(start, Instant.now()));
        }
    }

    private record SpecPartition(boolean hasRoute, List<Message.ToolCallRef> readOnlyCalls) {
        static final SpecPartition EMPTY = new SpecPartition(false, List.of());
    }

    private sealed interface FirstTurnOutcome {
        record Blocked(GuardResult guardResult) implements FirstTurnOutcome {}
        record Committed(ChatResponse response, Duration elapsed,
                         Map<String, SpeculativeOutcome> speculatives) implements FirstTurnOutcome {}
    }

    private record SpeculativeOutcome(RunnerCore.ToolCallOutcome outcome, ToolFailure failure, Duration elapsed) {}

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
            GuardResult blocking = core.runInputBlocking(rootAgent, ctx, input, tctx);
            if (blocking.blocked()) {
                var blockedEvent = new Event.Blocked(rootAgent.name(), blocking.guard(), blocking.message());
                out.accept(blockedEvent);
                var reply = Reply.blocked(blocking, refOf(rootAgent), Usage.ZERO, tctx.traceId(), List.copyOf(transcript));
                out.accept(new Event.Done(rootAgent.name(), reply));
                return;
            }

            ParallelGuardHandle guardHandle = core.startInputParallel(rootAgent, ctx, input, tctx);

            List<Message> history = core.loadHistory(conversationId);
            history.add(new Message.User(input));

            Agent<T> current = rootAgent;
            Usage accumulatedUsage = Usage.ZERO;
            String lastText = "";
            JsonNode lastStructured = null;

            int maxTurns = core.effectiveMaxTurns(rootAgent);
            int turns = 0;
            boolean toolChoiceSatisfied = false;
            boolean firstTurn = true;
            outer:
            while (turns++ < maxTurns) {
                ChatRequest req = core.buildRequest(current, Collections.unmodifiableList(history), ctx, true, toolChoiceSatisfied);
                core.trace(tctx, new TraceEvent.LlmRequest(Instant.now(), current.name(), req));
                ModelProvider provider = core.pickProvider(current.model());

                var turnAccum = new TurnAccumulator();
                String currentAgentName = current.name();

                Duration elapsed;
                Map<String, SpeculativeOutcome> speculativeResults = Map.of();

                if (firstTurn) {
                    StreamFirstTurnOutcome ftr = raceStreamFirstTurn(
                            provider, req, guardHandle, current, ctx, delegateRunner,
                            turnAccum, currentAgentName, out);
                    if (ftr instanceof StreamFirstTurnOutcome.Blocked b) {
                        out.accept(new Event.Blocked(current.name(), b.guardResult().guard(), b.guardResult().message()));
                        var reply = Reply.blocked(b.guardResult(), refOf(rootAgent), Usage.ZERO, tctx.traceId(), List.copyOf(transcript));
                        out.accept(new Event.Done(rootAgent.name(), reply));
                        return;
                    }
                    var c = (StreamFirstTurnOutcome.Committed) ftr;
                    elapsed = c.elapsed();
                    speculativeResults = c.speculatives();
                    firstTurn = false;
                } else {
                    Instant start = Instant.now();
                    provider.chatStream(req, chunk -> dispatchChunk(chunk, currentAgentName, turnAccum, out));
                    elapsed = Duration.between(start, Instant.now());
                }

                var fakeResp = new ChatResponse(turnAccum.text.toString(), turnAccum.finalToolCalls(),
                        turnAccum.usage == null ? Usage.ZERO : turnAccum.usage,
                        "stop", current.model(), null);
                core.trace(tctx, new TraceEvent.LlmResponse(Instant.now(), current.name(), fakeResp, elapsed, fakeResp.usage()));
                accumulatedUsage = accumulatedUsage.plus(fakeResp.usage());

                history.add(new Message.Assistant(fakeResp.content(), fakeResp.toolCalls()));
                lastText = fakeResp.content();

                if (!fakeResp.hasToolCalls()) {
                    GuardResult after = core.runOutput(current, ctx, lastText, tctx);
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
                    var outcomes = core.appendRoutedToolOutputs(history, fakeResp.toolCalls(), routeTarget);
                    for (var o : outcomes) {
                        if (!o.taken()) {
                            out.accept(new Event.ToolResult(
                                    current.name(), o.call().name(), o.resultJson(), Duration.ZERO));
                            core.trace(tctx, new TraceEvent.ToolResult(
                                    Instant.now(), current.name(), o.call().name(), o.resultJson(), Duration.ZERO));
                        }
                    }
                    current = routeTarget;
                    toolChoiceSatisfied = false;
                    continue outer;
                }

                for (var call : fakeResp.toolCalls()) {
                    out.accept(new Event.ToolCall(current.name(), call.name(), call.argsJson()));
                    core.trace(tctx, new TraceEvent.ToolCall(Instant.now(), current.name(), call.name(), call.argsJson()));
                    SpeculativeOutcome spec = speculativeResults.get(call.id());
                    String result;
                    Duration toolElapsed;
                    if (spec != null) {
                        toolElapsed = spec.elapsed();
                        if (spec.failure() != null) {
                            out.accept(new Event.Error(current.name(), spec.failure()));
                            core.trace(tctx, new TraceEvent.Error(Instant.now(), current.name(), spec.failure().getMessage(), spec.failure()));
                            result = errorPayload(spec.failure());
                        } else {
                            accumulatedUsage = accumulatedUsage.plus(spec.outcome().usage());
                            result = spec.outcome().resultJson();
                        }
                    } else {
                        Instant toolStart = Instant.now();
                        RunnerCore.ToolCallOutcome outcome;
                        try {
                            outcome = core.executeToolCall(current, call.name(), call.argsJson(), ctx, delegateRunner);
                        } catch (ToolFailure f) {
                            out.accept(new Event.Error(current.name(), f));
                            core.trace(tctx, new TraceEvent.Error(Instant.now(), current.name(), f.getMessage(), f));
                            outcome = new RunnerCore.ToolCallOutcome(errorPayload(f), Usage.ZERO);
                        }
                        result = outcome.resultJson();
                        accumulatedUsage = accumulatedUsage.plus(outcome.usage());
                        toolElapsed = Duration.between(toolStart, Instant.now());
                    }
                    out.accept(new Event.ToolResult(current.name(), call.name(), result, toolElapsed));
                    core.trace(tctx, new TraceEvent.ToolResult(Instant.now(), current.name(), call.name(), result, toolElapsed));
                    history.add(new Message.Tool(call.id(), call.name(), result));
                }
                toolChoiceSatisfied = true;
            }

            core.saveHistory(conversationId, history);
            var reply = Reply.maxTurns(lastText, refOf(current), accumulatedUsage, tctx.traceId(), List.copyOf(transcript));
            out.accept(new Event.Done(current.name(), reply));
        } catch (RuntimeException e) {
            out.accept(new Event.Error(rootAgent.name(), e));
            throw e;
        } finally {
            core.endTrace(tctx);
        }
    }

    /**
     * Streaming first-turn race. BUFFER mode holds every downstream event in a {@link StreamBuffer}
     * until the guards resolve (flush on pass, discard on block). PASSTHROUGH mode emits events
     * live and gates further emission via an {@link AtomicBoolean} if a guard blocks. Either way,
     * tool execution — and therefore all {@code Event.ToolCall}/{@code Event.ToolResult}/
     * {@code Event.Transfer} events — happens only after guard resolution, so they never leak on
     * a blocked run regardless of mode.
     */
    private <T> StreamFirstTurnOutcome raceStreamFirstTurn(ModelProvider provider,
                                                           ChatRequest req,
                                                           ParallelGuardHandle guardHandle,
                                                           Agent<T> current,
                                                           T ctx,
                                                           RunnerCore.DelegateRunner delegateRunner,
                                                           TurnAccumulator turnAccum,
                                                           String currentAgentName,
                                                           Consumer<Event> realOut) {
        boolean buffer = guardHandle.anyBuffer();
        AtomicBoolean emitting = new AtomicBoolean(true);
        StreamBuffer streamBuffer = buffer ? new StreamBuffer(realOut) : null;
        Consumer<Event> streamingOut = buffer
                ? streamBuffer::accept
                : e -> { if (emitting.get()) realOut.accept(e); };

        Instant llmStart = Instant.now();
        CompletableFuture<Void> llmFuture = CompletableFuture.runAsync(
                () -> provider.chatStream(req, chunk -> dispatchChunk(chunk, currentAgentName, turnAccum, streamingOut)),
                core.vexec());

        Object winner;
        try {
            winner = CompletableFuture.anyOf(guardHandle.firstBlock(), llmFuture).join();
        } catch (CompletionException ce) {
            GuardResult gr = guardHandle.completion().join();
            if (gr.blocked()) {
                emitting.set(false);
                if (streamBuffer != null) streamBuffer.discardAndSeal();
                guardHandle.cancelAll();
                return new StreamFirstTurnOutcome.Blocked(gr);
            }
            Throwable cause = Throwables.unwrapCompletion(ce);
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }

        if (winner instanceof GuardResult gr && gr.blocked()) {
            emitting.set(false);
            if (streamBuffer != null) streamBuffer.discardAndSeal();
            llmFuture.cancel(true);
            guardHandle.cancelAll();
            return new StreamFirstTurnOutcome.Blocked(gr);
        }

        var partition = partitionForSpeculation(turnAccum.finalToolCalls(), current);
        var specFutures = dispatchSpeculatives(partition.readOnlyCalls(), current, ctx, delegateRunner);

        GuardResult guardResult = guardHandle.completion().join();
        if (guardResult.blocked()) {
            emitting.set(false);
            if (streamBuffer != null) streamBuffer.discardAndSeal();
            cancelAll(specFutures);
            return new StreamFirstTurnOutcome.Blocked(guardResult);
        }

        if (streamBuffer != null) streamBuffer.flushAndSeal(realOut);

        Map<String, SpeculativeOutcome> results = collectSpeculatives(partition.readOnlyCalls(), specFutures);
        Duration elapsed = Duration.between(llmStart, Instant.now());
        return new StreamFirstTurnOutcome.Committed(elapsed, results);
    }

    private sealed interface StreamFirstTurnOutcome {
        record Blocked(GuardResult guardResult) implements StreamFirstTurnOutcome {}
        record Committed(Duration elapsed, Map<String, SpeculativeOutcome> speculatives) implements StreamFirstTurnOutcome {}
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

    private String errorPayload(ToolFailure f) {
        return core.codec().writeValueAsString(Map.of("error", Map.of(
                "type", f.kind(),
                "tool", f.toolName(),
                "message", f.getMessage() == null ? "" : f.getMessage())));
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
