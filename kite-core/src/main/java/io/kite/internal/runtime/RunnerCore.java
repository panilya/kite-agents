package io.kite.internal.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kite.Agent;
import io.kite.ConversationStore;
import io.kite.Guard;
import io.kite.GuardResult;
import io.kite.Reply;
import io.kite.RunInterruptedException;
import io.kite.Tool;
import io.kite.ToolChoice;
import io.kite.ToolFailure;
import io.kite.internal.json.JsonCodec;
import io.kite.model.ChatRequest;
import io.kite.model.Message;
import io.kite.model.ModelProvider;
import io.kite.model.Usage;
import io.kite.schema.SchemaNode;
import io.kite.tracing.TraceContext;
import io.kite.tracing.TraceEvent;
import io.kite.tracing.TracingProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared logic between non-streaming and streaming runners. Owns everything around the provider
 * call: prompt assembly, tool dispatch, route resolution, guards, history persistence, tracing.
 */
public final class RunnerCore {

    private final List<ModelProvider> providers;
    private final ConversationStore store;
    private final TracingProvider tracer;
    private final GuardExecutor guardExecutor;
    private final int defaultMaxTurns;
    private final Duration toolTimeout;
    private final ExecutorService vexec;

    public RunnerCore(List<ModelProvider> providers,
                      ConversationStore store,
                      TracingProvider tracer,
                      GuardExecutor guardExecutor,
                      int defaultMaxTurns,
                      Duration toolTimeout,
                      ExecutorService vexec) {
        this.providers = providers;
        this.store = store;
        this.tracer = tracer;
        this.guardExecutor = guardExecutor;
        this.defaultMaxTurns = defaultMaxTurns;
        this.toolTimeout = toolTimeout;
        this.vexec = vexec;
    }

    public ModelProvider pickProvider(String model) {
        for (var p : providers) {
            if (p.supports(model)) return p;
        }
        throw new IllegalStateException("No ModelProvider supports model '" + model
                + "'. Registered providers: " + providers.size());
    }

    public int effectiveMaxTurns(Agent<?> agent) {
        return agent.maxTurns() != null ? agent.maxTurns() : defaultMaxTurns;
    }

    public List<Message> loadHistory(String conversationId) {
        if (conversationId == null) return new ArrayList<>();
        return new ArrayList<>(store.load(conversationId));
    }

    public void saveHistory(String conversationId, List<Message> history) {
        if (conversationId == null) return;
        store.save(conversationId, history);
    }

    /**
     * Build a ChatRequest for one turn. Merges instructions, history, tool schemas, output schema.
     *
     * <p>{@code toolChoiceSatisfied} is true once the current agent has executed at least one tool
     * call in this run. When set, a static {@link ToolChoice.Specific} or {@link ToolChoice.Required}
     * directive is downgraded to {@link ToolChoice#auto()} so the model can synthesize a final text
     * answer instead of being forced to call the tool again. Resets on route transfer (the new
     * agent's directive applies fresh). Dynamic tool choice is unaffected — the user controls it.
     */
    public <T> ChatRequest buildRequest(Agent<T> agent, List<Message> history, T ctx, boolean stream,
                                        boolean toolChoiceSatisfied) {
        String instructions = renderInstructionsWithRoutePreamble(agent, ctx);
        List<ChatRequest.ToolSchema> toolSchemas = buildToolSchemas(agent);
        SchemaNode outputSchema = agent.outputSchema();
        String outputName = agent.outputType() == null ? null : agent.outputType().getSimpleName();
        ToolChoice toolChoice = resolveToolChoice(agent, ctx, toolChoiceSatisfied);
        validateToolChoice(toolChoice, toolSchemas, agent);
        return new ChatRequest(
                agent.model(),
                instructions,
                history,
                toolSchemas,
                toolChoice,
                agent.parallelToolCalls(),
                outputSchema,
                outputName,
                agent.temperature(),
                null,
                stream);
    }

    private <T> ToolChoice resolveToolChoice(Agent<T> agent, T ctx, boolean toolChoiceSatisfied) {
        if (agent.dynamicToolChoice() != null) {
            return agent.dynamicToolChoice().apply(ctx);
        }
        ToolChoice choice = agent.toolChoice();
        if (toolChoiceSatisfied
                && (choice instanceof ToolChoice.Specific || choice instanceof ToolChoice.Required)) {
            return ToolChoice.auto();
        }
        return choice;
    }

    private void validateToolChoice(ToolChoice choice,
                                    List<ChatRequest.ToolSchema> schemas,
                                    Agent<?> agent) {
        if (!(choice instanceof ToolChoice.Specific s)) return;
        for (var t : schemas) {
            if (t.name().equals(s.name())) return;
        }
        throw new IllegalStateException(
                "tool choice '" + s.name() + "' does not match any tool or route on agent '"
                        + agent.name() + "'. Available: " + schemaNames(schemas));
    }

    private static String schemaNames(List<ChatRequest.ToolSchema> schemas) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < schemas.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(schemas.get(i).name());
        }
        return sb.append("]").toString();
    }

    private <T> String renderInstructionsWithRoutePreamble(Agent<T> agent, T ctx) {
        String base = agent.renderInstructions(ctx);
        if (agent.routes().isEmpty() && agent.dynamicRoutes() == null) return base;
        StringBuilder sb = new StringBuilder(base == null ? "" : base);
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("You can transfer this conversation to another specialist by calling the ")
                .append("appropriate route tool. Available specialists:");
        for (var r : agent.routes()) {
            sb.append("\n- ").append(r.name()).append(": ").append(r.description());
        }
        return sb.toString();
    }

    private <T> List<ChatRequest.ToolSchema> buildToolSchemas(Agent<T> agent) {
        List<ChatRequest.ToolSchema> out = new ArrayList<>(agent.tools().size() + agent.routes().size());
        for (var tool : agent.tools()) {
            out.add(new ChatRequest.ToolSchema(tool.name(), tool.description(), tool.paramsSchemaJson()));
        }
        for (var route : agent.routes()) {
            String routeName = routeToolName(route.name());
            String desc = "Transfer the conversation to " + route.name()
                    + (route.description().isEmpty() ? "" : ": " + route.description());
            out.add(new ChatRequest.ToolSchema(routeName, desc, emptyObjectSchema()));
        }
        return out;
    }

    public static String routeToolName(String targetName) {
        return "transfer_to_" + targetName;
    }

    private static String emptyObjectSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    /** Resolve a tool call that might be a route. Returns the target agent if so, null otherwise. */
    public <T> Agent<T> resolveRoute(Agent<T> agent, String toolCallName) {
        if (!toolCallName.startsWith("transfer_to_")) return null;
        String targetName = toolCallName.substring("transfer_to_".length());
        for (var r : agent.routes()) {
            if (r.name().equals(targetName)) return r;
        }
        return null;
    }

    /**
     * Execute a single tool call and return both the JSON result and the {@link Usage} contributed
     * by the call. For FUNCTION tools usage is {@link Usage#ZERO}; for DELEGATE tools it is the
     * delegate's accumulated usage. {@code delegateRunner} is required when the agent has any
     * DELEGATE tools — Runner always supplies one.
     *
     * <p>FUNCTION invocations run on the virtual-thread executor with {@link #toolTimeout}.
     * DELEGATE invocations intentionally bypass that timeout: a delegate runs nested LLM calls
     * whose natural bound is the delegate's own {@code maxTurns}, not the tool timeout.
     */
    public ToolCallOutcome executeToolCall(Agent<?> agent,
                                           String toolName,
                                           String argsJson,
                                           Object ctx,
                                           DelegateRunner delegateRunner) {
        Tool tool = findTool(agent, toolName);
        if (tool == null) {
            throw new ToolFailure.NotRegistered(toolName,
                    "Tool '" + toolName + "' not registered on agent '" + agent.name() + "'");
        }
        if (tool.kind() == Tool.Kind.DELEGATE) {
            try {
                return executeDelegate(tool, argsJson, ctx, delegateRunner);
            } catch (RunInterruptedException | ToolFailure e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ToolFailure.ThrownByTool(tool.name(),
                        "Delegate '" + tool.name() + "' failed: " + Throwables.describe(e), e);
            }
        }
        var invoker = tool.invoker();
        Future<String> f = vexec.submit(() -> invoker.invoke(ctx, argsJson));
        try {
            return new ToolCallOutcome(f.get(toolTimeout.toMillis(), TimeUnit.MILLISECONDS), Usage.ZERO);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new ToolFailure.Timeout(toolName,
                    "Tool '" + toolName + "' timed out after " + toolTimeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            throw new RunInterruptedException(
                    "Run interrupted during tool '" + toolName + "'", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ToolFailure tf) throw tf;
            throw new ToolFailure.ThrownByTool(toolName,
                    "Tool '" + toolName + "' failed: " + Throwables.describe(cause), cause);
        }
    }

    private ToolCallOutcome executeDelegate(Tool tool, String argsJson, Object parentCtx, DelegateRunner delegateRunner) {
        if (delegateRunner == null) {
            throw new IllegalStateException("DELEGATE tool '" + tool.name() + "' invoked without a DelegateRunner");
        }
        JsonNode args = codec().readTreeOrEmpty(argsJson);
        JsonNode inputNode = args.get("input");
        String input = inputNode == null || inputNode.isNull() ? "" : inputNode.asText();

        Agent<?> target = tool.routeTarget();
        Object injectedCtx = target.contextType() == Void.class ? null : parentCtx;

        Reply reply = delegateRunner.run(target, input, injectedCtx);

        ObjectNode payload = codec().mapper().createObjectNode();
        switch (reply.status()) {
            case BLOCKED -> {
                payload.put("blocked", true);
                payload.put("guard", reply.guardResult() == null ? "" : reply.guardResult().guard());
                payload.put("message", reply.blockReason() == null ? "" : reply.blockReason());
            }
            case MAX_TURNS -> {
                payload.put("max_turns", true);
                payload.put("text", reply.text() == null ? "" : reply.text());
            }
            case OK -> {
                if (tool.outputExtractor() != null) {
                    payload.put("output", tool.outputExtractor().apply(reply));
                } else if (reply.structuredOutput() != null) {
                    payload.set("output", reply.structuredOutput());
                } else {
                    payload.put("output", reply.text() == null ? "" : reply.text());
                }
            }
        }
        return new ToolCallOutcome(payload.toString(), reply.usage());
    }

    /** Result of a single tool call: the JSON result plus any usage the call contributed. */
    public record ToolCallOutcome(String resultJson, Usage usage) {}

    /** Dispatches a delegated sub-agent run; {@code Runner} supplies the implementation. */
    @FunctionalInterface
    public interface DelegateRunner {
        Reply run(Agent<?> delegate, String input, Object ctx);
    }

    private Tool findTool(Agent<?> agent, String name) {
        for (var t : agent.tools()) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }

    public <T> GuardResult runInputBlocking(Agent<T> agent, T ctx, String input, TraceContext tctx) {
        return guardExecutor.runBlocking(agent.inputGuards(), ctx, input,
                (g, r) -> emitGuardCheck(tctx, agent.name(), g, r));
    }

    public <T> GuardResult runInputParallel(Agent<T> agent, T ctx, String input, TraceContext tctx) {
        return guardExecutor.runParallel(agent.inputGuards(), ctx, input,
                (g, r) -> emitGuardCheck(tctx, agent.name(), g, r));
    }

    public <T> GuardResult runOutput(Agent<T> agent, T ctx, String output, TraceContext tctx) {
        return guardExecutor.runAfter(agent.outputGuards(), ctx, output,
                (g, r) -> emitGuardCheck(tctx, agent.name(), g, r));
    }

    private void emitGuardCheck(TraceContext tctx, String agentName, Guard<?> g, GuardResult r) {
        trace(tctx, new TraceEvent.GuardCheck(
                Instant.now(),
                agentName,
                g.name(),
                g.phase().name(),
                r.passing(),
                r.message()));
    }

    public TraceContext startTrace(Agent<?> rootAgent, String conversationId) {
        var tctx = new TraceContext(
                "trace-" + UUID.randomUUID(),
                conversationId,
                rootAgent.name(),
                Instant.now(),
                Map.of());
        safeTrace(() -> tracer.onRunStart(tctx));
        return tctx;
    }

    public void endTrace(TraceContext tctx) {
        safeTrace(() -> tracer.onRunEnd(tctx));
    }

    public void trace(TraceContext tctx, TraceEvent event) {
        safeTrace(() -> tracer.onEvent(tctx, event));
    }

    private void safeTrace(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            if (tracerFailureLogged.compareAndSet(false, true)) {
                System.err.println("[kite] tracing provider " + tracer.getClass().getName()
                        + " threw " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + " (further failures suppressed)");
            }
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean tracerFailureLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public JsonCodec codec() {
        return JsonCodec.shared();
    }

    /**
     * Append a synthetic {@link Message.Tool} output for every tool call in the assistant's
     * response when a route was taken. Required for providers that enforce
     * function_call / function_call_output pairing (OpenAI Responses API, Anthropic Messages API).
     * The taken-route call gets a {@code transferred_to} marker; any other calls in the same
     * response get a {@code skipped} marker so the target agent can see they were observed but
     * not executed (important when the LLM emits a route alongside a real tool call like
     * {@code refund} — the target must not assume the refund already ran).
     *
     * <p>Returns the per-call routing outcomes so the caller can emit observability events for
     * each one (trace events on the non-streaming path, {@link io.kite.Event} on streaming).
     */
    public List<RoutedCallOutcome> appendRoutedToolOutputs(List<Message> history,
                                                           List<Message.ToolCallRef> toolCalls,
                                                           Agent<?> routeTarget) {
        String takenCallName = routeToolName(routeTarget.name());
        String takenOutput = codec().writeValueAsString(Map.of("transferred_to", routeTarget.name()));
        String skippedOutput = codec().writeValueAsString(Map.of(
                "skipped", true,
                "reason", "transferred_to_" + routeTarget.name()));
        List<RoutedCallOutcome> outcomes = new ArrayList<>(toolCalls.size());
        for (var call : toolCalls) {
            boolean taken = takenCallName.equals(call.name());
            String output = taken ? takenOutput : skippedOutput;
            history.add(new Message.Tool(call.id(), call.name(), output));
            outcomes.add(new RoutedCallOutcome(call, taken, output));
        }
        return outcomes;
    }

    /** Per-call result from {@link #appendRoutedToolOutputs}. */
    public record RoutedCallOutcome(Message.ToolCallRef call, boolean taken, String resultJson) {}
}
