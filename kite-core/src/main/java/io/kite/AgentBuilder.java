package io.kite;

import io.kite.internal.runtime.RunnerCore;
import io.kite.internal.runtime.ToolInvoker;
import io.kite.internal.runtime.ToolInvokerFactory;
import io.kite.schema.JsonSchemaGenerator;
import io.kite.schema.SchemaNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Fluent builder for {@link Agent}. Type-safe on the context type {@code <T>}: {@code .route(other)}
 * only accepts agents whose context matches, and {@code .tools(bean)} validates that any
 * {@code @Ctx} parameters are assignable to {@code T}.
 */
public final class AgentBuilder<T> {

    private final String model;
    private final Class<T> contextType;
    private String name = "agent";
    private String description = "";
    private final List<Function<T, String>> instructionFns = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();
    private final List<Agent<T>> routes = new ArrayList<>();
    private Function<T, List<Agent<T>>> dynamicRoutes;
    private final List<Guard<T>> beforeGuards = new ArrayList<>();
    private final List<Guard<T>> afterGuards = new ArrayList<>();
    private Class<?> outputType;
    private SchemaNode outputSchema;
    private Double temperature;
    private Integer maxTurns;
    private ToolChoice toolChoice;
    private Function<T, ToolChoice> dynamicToolChoice;
    private Boolean parallelToolCalls;

    AgentBuilder(String model, Class<T> contextType) {
        this.model = Objects.requireNonNull(model, "model");
        this.contextType = Objects.requireNonNull(contextType, "contextType");
    }

    public AgentBuilder<T> name(String name) {
        this.name = Objects.requireNonNull(name, "name");
        return this;
    }

    public AgentBuilder<T> description(String description) {
        this.description = description == null ? "" : description;
        return this;
    }

    public AgentBuilder<T> instructions(String staticText) {
        if (staticText != null && !staticText.isEmpty()) {
            instructionFns.add(ctx -> staticText);
        }
        return this;
    }

    public AgentBuilder<T> instructions(Function<T, String> fn) {
        if (fn != null) instructionFns.add(fn);
        return this;
    }

    /** Register all {@code @Tool}-annotated methods on the given bean. */
    public AgentBuilder<T> tools(Object bean) {
        List<Tool> scanned = ToolInvokerFactory.scan(bean, contextType == Void.class ? null : contextType);
        tools.addAll(scanned);
        return this;
    }

    /** Register a single programmatic or externally-built tool. */
    public AgentBuilder<T> tool(Tool tool) {
        tools.add(Objects.requireNonNull(tool, "tool"));
        return this;
    }

    /** Register a transfer route to another agent. Compiler enforces matching {@code <T>}. */
    public AgentBuilder<T> route(Agent<T> target) {
        routes.add(Objects.requireNonNull(target, "target"));
        return this;
    }

    public AgentBuilder<T> dynamicRoutes(Function<T, List<Agent<T>>> fn) {
        this.dynamicRoutes = fn;
        return this;
    }

    public AgentBuilder<T> before(Guard<T> guard) {
        beforeGuards.add(Objects.requireNonNull(guard, "guard"));
        return this;
    }

    public AgentBuilder<T> after(Guard<T> guard) {
        afterGuards.add(Objects.requireNonNull(guard, "guard"));
        return this;
    }

    public AgentBuilder<T> output(Class<? extends Record> type) {
        this.outputType = Objects.requireNonNull(type, "output type");
        this.outputSchema = JsonSchemaGenerator.forRecord(type);
        return this;
    }

    public AgentBuilder<T> temperature(double t) {
        this.temperature = t;
        return this;
    }

    public AgentBuilder<T> maxTurns(int n) {
        this.maxTurns = n;
        return this;
    }

    /**
     * Set a static tool-choice directive for every LLM call this agent makes. Mutually exclusive
     * with {@link #toolChoice(Function)}; setting one clears the other. Pass {@code null} to
     * fall back to provider default (equivalent to {@link ToolChoice#auto()}).
     *
     * <p>When {@code choice} is {@link ToolChoice.Specific}, the tool name is validated at
     * {@link #build()} time against the tools and routes currently registered on this builder.
     * A mismatch throws {@link IllegalStateException}.
     */
    public AgentBuilder<T> toolChoice(ToolChoice choice) {
        this.toolChoice = choice;
        this.dynamicToolChoice = null;
        return this;
    }

    /**
     * Set a dynamic tool-choice resolver that receives the typed context at request time and
     * returns the directive to apply for that call. Mirrors {@link #instructions(Function)} and
     * {@link #dynamicRoutes(Function)}. Mutually exclusive with {@link #toolChoice(ToolChoice)}.
     *
     * <p>Returning {@code null} from the function is equivalent to the provider default.
     * Unknown {@link ToolChoice.Specific} names are caught per-turn at request-build time with
     * a clear error listing the current tool set.
     */
    public AgentBuilder<T> toolChoice(Function<T, ToolChoice> fn) {
        this.dynamicToolChoice = fn;
        this.toolChoice = null;
        return this;
    }

    /**
     * Control whether the model may emit multiple tool calls in a single assistant turn.
     * Default ({@code null}) is the provider default — typically parallel. Setting {@code false}
     * forces the model to emit at most one tool call per turn, useful when downstream tool
     * execution has order dependencies.
     *
     * <p>Note: this is a <em>hint to the model</em> about how many tool calls to emit. Kite
     * executes tool calls sequentially within a turn regardless of this setting.
     */
    public AgentBuilder<T> parallelToolCalls(boolean enabled) {
        this.parallelToolCalls = enabled;
        return this;
    }

    public Agent<T> build() {
        validateStaticToolChoice();
        return new Agent<>(
                model, name, description,
                instructionFns,
                tools,
                routes,
                dynamicRoutes,
                beforeGuards,
                afterGuards,
                outputType,
                outputSchema,
                temperature,
                maxTurns,
                toolChoice,
                dynamicToolChoice,
                parallelToolCalls,
                contextType);
    }

    private void validateStaticToolChoice() {
        if (!(toolChoice instanceof ToolChoice.Specific s)) return;
        for (Tool t : tools) {
            if (t.name().equals(s.name())) return;
        }
        for (Agent<T> r : routes) {
            if (RunnerCore.routeToolName(r.name()).equals(s.name())) return;
        }
        throw new IllegalStateException(
                "tool choice '" + s.name() + "' does not match any tool or route on agent '" + name + "'");
    }
}
