package io.kite.internal.runtime;

import io.kite.Agent;
import io.kite.AgentRef;
import io.kite.Event;
import io.kite.GuardResult;
import io.kite.Reply;
import io.kite.RunInterruptedException;
import io.kite.Tool;
import io.kite.ToolFailure;
import io.kite.model.ChatRequest;
import io.kite.model.ChatResponse;
import io.kite.model.Message;
import io.kite.model.ModelProvider;
import io.kite.model.Usage;
import io.kite.tracing.TraceContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * The framework's turn-execution state machine. One {@link #execute} method drives every run,
 * parameterized by an {@link LlmTurn} (chat vs stream) and a {@link RunEmitter} (trace-only
 * vs trace+event). Public entry points are {@link #run} and {@link #stream}; delegation
 * (Agent-as-Tool) reuses {@code execute} under the parent's {@link TraceContext} so the
 * delegate's internal events appear inline under the same trace id.
 *
 * <p>On the first turn, parallel input guards race the LLM call on the shared virtual-thread
 * executor. Read-only tool calls from that response can start in parallel with still-running
 * guards — their results are thrown away if any guard blocks. Subsequent turns proceed
 * synchronously since guards are already resolved.
 */
public final class Runner {

    private final RunnerCore core;

    public Runner(RunnerCore core) {
        this.core = core;
    }

    /* ================================ Public entry points ================================ */

    public <T> Reply run(Agent<T> agent, String input, String conversationId, T ctx) {
        return topLevel(agent, input, conversationId, ctx, LlmTurn.CHAT, null);
    }

    public <T> void stream(Agent<T> agent, String input, String conversationId, T ctx,
                           Consumer<Event> sink) {
        topLevel(agent, input, conversationId, ctx, LlmTurn.STREAM, sink);
    }

    private <T> Reply topLevel(Agent<T> agent, String input, String conversationId, T ctx,
                               LlmTurn turn, Consumer<Event> sink) {
        TraceContext tctx = core.startTrace(agent, conversationId);
        RunEmitter emitter = sink == null
                ? RunEmitter.traceOnly(core, tctx)
                : RunEmitter.streaming(core, tctx, sink);
        try {
            Reply reply = execute(agent, input, conversationId, ctx, turn, emitter, tctx);
            emitter.done(reply.agent().name(), reply);
            return reply;
        } catch (RuntimeException e) {
            emitter.error(agent.name(), e);
            throw e;
        } finally {
            core.endTrace(tctx);
        }
    }

    /* =================================== The one loop =================================== */

    private <T> Reply execute(Agent<T> root, String input, String conversationId, T ctx,
                              LlmTurn turn, RunEmitter emitter, TraceContext tctx) {
        GuardResult blocking = core.runInputBlocking(root, ctx, input, tctx);
        if (blocking.blocked()) {
            emitter.blocked(root.name(), blocking.guard(), blocking.message());
            return Reply.blocked(blocking, refOf(root), Usage.ZERO, tctx.traceId(), emitter.transcript());
        }

        ParallelGuardHandle guards = core.startInputParallel(root, ctx, input, tctx);

        List<Message> history = core.loadHistory(conversationId);
        history.add(new Message.User(input));
        TurnState<T> state = new TurnState<>(root, history);

        boolean streaming = (turn == LlmTurn.STREAM);
        RunnerCore.DelegateRunner delegate = (d, i, c) -> runDelegate(d, i, c, tctx);

        int maxTurns = core.effectiveMaxTurns(root);
        int turns = 0;
        while (turns++ < maxTurns) {
            ChatRequest req = core.buildRequest(
                    state.current, Collections.unmodifiableList(history), ctx, streaming, state.toolChoiceSatisfied);
            emitter.llmRequest(state.current.name(), req);
            ModelProvider provider = core.pickProvider(state.current.model());

            ChatResponse resp;
            Duration elapsed;
            Map<String, SpeculativeOutcome> specs = Map.of();

            if (state.firstTurn) {
                FirstTurnOutcome outcome = raceFirstTurn(turn, provider, req, state.current, ctx, delegate, guards, emitter);
                if (outcome instanceof FirstTurnOutcome.Blocked(GuardResult gr)) {
                    emitter.blocked(root.name(), gr.guard(), gr.message());
                    return Reply.blocked(gr, refOf(root), Usage.ZERO, tctx.traceId(), emitter.transcript());
                }
                var committed = (FirstTurnOutcome.Committed) outcome;
                resp = committed.response();
                elapsed = committed.elapsed();
                specs = committed.speculatives();
                state.firstTurn = false;
            } else {
                Instant llmStart = Instant.now();
                resp = turn.run(provider, req, state.current.name(), emitter);
                elapsed = Duration.between(llmStart, Instant.now());
            }

            emitter.llmResponse(state.current.name(), resp, elapsed);
            state.usage = state.usage.plus(resp.usage());
            history.add(new Message.Assistant(resp.content(), resp.toolCalls()));
            state.lastText = resp.content() == null ? "" : resp.content();

            if (!resp.hasToolCalls()) {
                GuardResult after = core.runOutput(state.current, ctx, state.lastText, tctx);
                if (after.blocked()) {
                    emitter.blocked(state.current.name(), after.guard(), after.message());
                    return Reply.blocked(after, refOf(state.current), state.usage, tctx.traceId(), emitter.transcript());
                }
                if (state.current.outputType() != null && !state.lastText.isEmpty()) {
                    state.lastStructured = core.codec().readTree(state.lastText);
                }
                core.saveHistory(conversationId, history);
                return Reply.ok(state.lastText, refOf(state.current), state.lastStructured,
                        state.usage, tctx.traceId(), emitter.transcript());
            }

            Agent<T> routeTarget = detectRoute(state.current, resp.toolCalls());
            if (routeTarget != null) {
                handleRoute(state, emitter, resp.toolCalls(), routeTarget);
                continue;
            }

            executeToolCalls(state, ctx, emitter, resp.toolCalls(), specs, delegate);
            state.toolChoiceSatisfied = true;
        }

        core.saveHistory(conversationId, history);
        return Reply.maxTurns(state.lastText, refOf(state.current), state.usage, tctx.traceId(), emitter.transcript());
    }

    /**
     * Run a delegated sub-agent under the parent's trace context. History is fresh (no
     * conversation store read or write) so the delegate sees only {@code input} as its single
     * user message. Delegates always use the non-streaming {@link LlmTurn#CHAT} because the
     * delegate's own events would be noise in the outer stream — the delegate's reply is
     * serialized into a tool result by {@link RunnerCore#executeToolCall}. The unchecked
     * cast is safe because {@link io.kite.AgentBuilder#build()} verifies that every DELEGATE
     * tool target has a compatible context type.
     */
    private Reply runDelegate(Agent<?> delegate, String input, Object ctx, TraceContext parentTctx) {
        RunEmitter delegateEmitter = RunEmitter.traceOnly(core, parentTctx);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Reply reply = execute((Agent) delegate, input, null, ctx, LlmTurn.CHAT, delegateEmitter, parentTctx);
        return reply;
    }

    /* ============================= First-turn race orchestration ============================= */

    private <T> FirstTurnOutcome raceFirstTurn(LlmTurn turn,
                                               ModelProvider provider,
                                               ChatRequest req,
                                               Agent<T> current,
                                               T ctx,
                                               RunnerCore.DelegateRunner delegate,
                                               ParallelGuardHandle guards,
                                               RunEmitter emitter) {
        emitter.beginFirstTurnGating(guards.anyBuffer());
        Instant raceStart = Instant.now();
        String agentName = current.name();
        CompletableFuture<ChatResponse> llmFuture = CompletableFuture.supplyAsync(
                () -> turn.run(provider, req, agentName, emitter), core.vexec());

        FirstTurnRace.StepResult step = FirstTurnRace.awaitFirstStep(llmFuture, guards);
        if (step instanceof FirstTurnRace.StepResult.EarlyBlock(GuardResult gr)) {
            llmFuture.cancel(true);
            guards.cancelAll();
            emitter.firstTurnBlock();
            return new FirstTurnOutcome.Blocked(gr);
        }

        ChatResponse resp = ((FirstTurnRace.StepResult.LlmReady) step).response();

        SpecPartition partition = partitionForSpeculation(resp.toolCalls(), current);
        Map<String, CompletableFuture<SpeculativeOutcome>> specFutures =
                dispatchSpeculatives(partition.readOnlyCalls(), current, ctx, delegate);

        GuardResult guardResult = guards.completion().join();
        if (guardResult.blocked()) {
            cancelAll(specFutures);
            emitter.firstTurnBlock();
            return new FirstTurnOutcome.Blocked(guardResult);
        }

        emitter.firstTurnCommit();
        Map<String, SpeculativeOutcome> specs = collectSpeculatives(partition.readOnlyCalls(), specFutures);
        Duration elapsed = Duration.between(raceStart, Instant.now());
        return new FirstTurnOutcome.Committed(resp, elapsed, specs);
    }

    /* ================================== Turn-level helpers ================================== */

    private <T> Agent<T> detectRoute(Agent<T> current, List<Message.ToolCallRef> calls) {
        for (var call : calls) {
            Agent<T> target = core.resolveRoute(current, call.name());
            if (target != null) return target;
        }
        return null;
    }

    private <T> void handleRoute(TurnState<T> state, RunEmitter emitter,
                                 List<Message.ToolCallRef> calls, Agent<T> target) {
        emitter.transfer(state.current.name(), target.name());
        List<RunnerCore.RoutedCallOutcome> outcomes =
                core.appendRoutedToolOutputs(state.history, calls, target);
        for (var o : outcomes) {
            if (!o.taken()) {
                emitter.toolResult(state.current.name(), o.call().name(), o.resultJson(), Duration.ZERO);
            }
        }
        state.current = target;
        state.toolChoiceSatisfied = false;
    }

    private <T> void executeToolCalls(TurnState<T> state, T ctx, RunEmitter emitter,
                                      List<Message.ToolCallRef> calls,
                                      Map<String, SpeculativeOutcome> specs,
                                      RunnerCore.DelegateRunner delegate) {
        for (var call : calls) {
            emitter.toolCall(state.current.name(), call);
            SpeculativeOutcome spec = specs.get(call.id());
            String result;
            Duration elapsed;
            if (spec != null) {
                elapsed = spec.elapsed();
                if (spec.failure() != null) {
                    emitter.error(state.current.name(), spec.failure());
                    result = errorPayload(spec.failure());
                } else {
                    state.usage = state.usage.plus(spec.outcome().usage());
                    result = spec.outcome().resultJson();
                }
            } else {
                Instant start = Instant.now();
                RunnerCore.ToolCallOutcome outcome;
                try {
                    outcome = core.executeToolCall(state.current, call.name(), call.argsJson(), ctx, delegate);
                } catch (ToolFailure f) {
                    emitter.error(state.current.name(), f);
                    outcome = new RunnerCore.ToolCallOutcome(errorPayload(f), Usage.ZERO);
                }
                result = outcome.resultJson();
                state.usage = state.usage.plus(outcome.usage());
                elapsed = Duration.between(start, Instant.now());
            }
            emitter.toolResult(state.current.name(), call.name(), result, elapsed);
            state.history.add(new Message.Tool(call.id(), call.name(), result));
        }
    }

    /* ================================== Speculative tools ================================== */

    /**
     * Single-pass partition of a tool-call batch into (has-route, read-only-calls). A route
     * in the batch wins globally — Kite transfers after the assistant message is appended —
     * so we don't speculate on anything when a route is present.
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
            List<Message.ToolCallRef> readOnlyCalls, Agent<T> agent, T ctx,
            RunnerCore.DelegateRunner delegate) {
        if (readOnlyCalls.isEmpty()) return Map.of();
        Map<String, CompletableFuture<SpeculativeOutcome>> futures = new HashMap<>(readOnlyCalls.size());
        for (var call : readOnlyCalls) {
            var f = CompletableFuture.supplyAsync(
                    () -> invokeSpeculatively(agent, call, ctx, delegate), core.vexec());
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

    private <T> SpeculativeOutcome invokeSpeculatively(Agent<T> agent, Message.ToolCallRef call,
                                                       T ctx, RunnerCore.DelegateRunner delegate) {
        Instant start = Instant.now();
        try {
            RunnerCore.ToolCallOutcome out = core.executeToolCall(agent, call.name(), call.argsJson(), ctx, delegate);
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

    record SpeculativeOutcome(RunnerCore.ToolCallOutcome outcome, ToolFailure failure, Duration elapsed) {}

    /* ===================================== Small utils ===================================== */

    private String errorPayload(ToolFailure f) {
        return core.codec().writeValueAsString(Map.of("error", Map.of(
                "type", f.kind(),
                "tool", f.toolName(),
                "message", f.getMessage() == null ? "" : f.getMessage())));
    }

    private static AgentRef refOf(Agent<?> agent) {
        return new AgentRef(agent.name(), agent.model());
    }
}
