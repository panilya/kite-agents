package io.kite;

import io.kite.internal.runtime.RunnerCore;
import io.kite.internal.runtime.ToolInvokerFactory;
import io.kite.schema.JsonSchemaGenerator;
import io.kite.schema.SchemaNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Fluent builder for {@link Agent}. Type-safe on the context type {@code <T>}: {@code .route(other)}
 * only accepts agents whose context matches, and {@code .tools(bean)} validates that any
 * {@code @Ctx} parameters are assignable to {@code T}.
 */
public final class AgentBuilder<T> {

    private final Class<T> contextType;
    private String model;
    private String name = "agent";
    private String description = "";
    private final List<Function<T, String>> instructionFns = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();
    private final List<Agent<T>> routes = new ArrayList<>();
    private Function<T, List<Agent<T>>> dynamicRoutes;
    private final List<Guard<T>> inputGuards = new ArrayList<>();
    private final List<Guard<T>> outputGuards = new ArrayList<>();
    private Class<? extends Record> outputType;
    private SchemaNode outputSchema;
    private Double temperature;
    private Integer maxTurns;
    private ToolChoice toolChoice;
    private Function<T, ToolChoice> dynamicToolChoice;
    private Boolean parallelToolCalls;

    AgentBuilder(Class<T> contextType) {
        this.contextType = Objects.requireNonNull(contextType, "contextType");
    }

    public AgentBuilder<T> model(String model) {
        this.model = Objects.requireNonNull(model, "model");
        return this;
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

    /**
     * Replace the current input-phase guards. Input guards run before the first LLM call and can
     * block the run early. Pass an empty list to clear.
     */
    public AgentBuilder<T> inputGuards(List<Guard<T>> guards) {
        replaceGuards(this.inputGuards, guards);
        return this;
    }

    /**
     * Replace the current output-phase guards. Output guards run on the model's final text and
     * can block the reply. Pass an empty list to clear.
     */
    public AgentBuilder<T> outputGuards(List<Guard<T>> guards) {
        replaceGuards(this.outputGuards, guards);
        return this;
    }

    private static <G> void replaceGuards(List<G> target, List<G> source) {
        Objects.requireNonNull(source, "guards");
        target.clear();
        for (G g : source) target.add(Objects.requireNonNull(g, "guard"));
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
        if (n < 1) throw new IllegalArgumentException("maxTurns must be >= 1");
        this.maxTurns = n;
        return this;
    }

    /**
     * Static tool-choice directive. Validated at {@link #build()} time against registered tools
     * and routes — a {@link ToolChoice.Specific} name mismatch throws {@link IllegalStateException}.
     *
     * <p>If {@link #dynamicToolChoice(Function)} is also set on this builder, the dynamic resolver
     * takes precedence at request time and this static value is ignored.
     */
    public AgentBuilder<T> toolChoice(ToolChoice choice) {
        this.toolChoice = choice;
        return this;
    }

    /**
     * Dynamic tool-choice resolver. Evaluated every turn with the agent's typed context; return
     * {@code null} for the provider default. Takes precedence over {@link #toolChoice(ToolChoice)}
     * when both are set.
     */
    public AgentBuilder<T> dynamicToolChoice(Function<T, ToolChoice> fn) {
        this.dynamicToolChoice = fn;
        return this;
    }

    /**
     * Hint to the model about whether it may emit multiple tool calls in a single assistant turn.
     * Default ({@code null}) is the provider default — typically parallel. Kite executes tool
     * calls sequentially within a turn regardless of this setting.
     */
    public AgentBuilder<T> parallelToolCalls(boolean enabled) {
        this.parallelToolCalls = enabled;
        return this;
    }

    public Agent<T> build() {
        if (model == null) {
            throw new IllegalStateException("model must be set via AgentBuilder.model(String) before build()");
        }
        validateDelegateTools();
        validateStaticToolChoice();
        return new Agent<>(
                model, name, description,
                instructionFns,
                tools,
                routes,
                dynamicRoutes,
                inputGuards,
                outputGuards,
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

    private void validateDelegateTools() {
        Set<String> toolNames = new HashSet<>();
        for (Tool t : tools) {
            if (!toolNames.add(t.name())) {
                throw new IllegalStateException(
                        "duplicate tool name '" + t.name() + "' on agent '" + name + "'");
            }
        }
        Set<String> routeTargetNames = new HashSet<>();
        Set<String> routeToolNames = new HashSet<>();
        for (Agent<T> r : routes) {
            if (!routeTargetNames.add(r.name())) {
                throw new IllegalStateException(
                        "duplicate route target name '" + r.name() + "' on agent '" + name
                                + "'. Use distinct .name(...) values on routed agents so transfer_to_<name>"
                                + " tools are unambiguous.");
            }
            routeToolNames.add(RunnerCore.routeToolName(r.name()));
        }
        for (Tool t : tools) {
            if (t.kind() != Tool.Kind.DELEGATE) continue;
            if (t.name().startsWith("transfer_to_")) {
                throw new IllegalStateException(
                        "delegate tool '" + t.name() + "' on agent '" + name
                                + "' uses reserved prefix 'transfer_to_'");
            }
            if (routeToolNames.contains(t.name())) {
                throw new IllegalStateException(
                        "delegate tool '" + t.name() + "' on agent '" + name
                                + "' collides with a synthetic route tool of the same name");
            }
            Agent<?> target = t.routeTarget();
            if (target == null) continue;
            Class<?> targetCtx = target.contextType();
            if (targetCtx != contextType && targetCtx != Void.class) {
                throw new IllegalStateException(
                        "delegate '" + target.name() + "' on agent '" + name
                                + "' has incompatible context type " + targetCtx.getName()
                                + "; expected " + contextType.getName() + " or Void");
            }
        }
    }
}
