package io.kite;

import io.kite.internal.runtime.ToolInvoker;
import io.kite.schema.SchemaNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Immutable description of an agent's behavior: model, instructions, tools, routes, guards,
 * optional structured output type. Agents carry no runtime state — the same instance can serve
 * a million concurrent conversations on different {@link Kite} instances.
 *
 * <p>Generic on a context type {@code <T>}. The context flows to instructions, guards, and tools
 * with compile-time type safety. Routes between agents must share the same {@code <T>}, enforced
 * by the builder signature.
 */
public final class Agent<T> {

    final String model;
    final String name;
    final String description;
    final List<Function<T, String>> instructionFns;
    final List<Tool> tools;
    final List<Agent<T>> routes;
    final Function<T, List<Agent<T>>> dynamicRoutes;
    final List<Guard<T>> inputGuards;
    final List<Guard<T>> outputGuards;
    final Class<? extends Record> outputType;   // nullable
    final SchemaNode outputSchema;              // nullable; built from outputType at build time
    final Double temperature;
    final Integer maxTurns;
    final ToolChoice toolChoice;                          // nullable — provider default when null
    final Function<T, ToolChoice> dynamicToolChoice;      // nullable — alternative to static
    final Boolean parallelToolCalls;                      // nullable — provider default when null
    final Class<T> contextType;          // never null — Void.class for no-context agents

    /** Internal — use {@link #builder()} / {@link #builder(Class)}. */
    Agent(String model,
          String name,
          String description,
          List<Function<T, String>> instructionFns,
          List<Tool> tools,
          List<Agent<T>> routes,
          Function<T, List<Agent<T>>> dynamicRoutes,
          List<Guard<T>> inputGuards,
          List<Guard<T>> outputGuards,
          Class<? extends Record> outputType,
          SchemaNode outputSchema,
          Double temperature,
          Integer maxTurns,
          ToolChoice toolChoice,
          Function<T, ToolChoice> dynamicToolChoice,
          Boolean parallelToolCalls,
          Class<T> contextType) {
        this.model = model;
        this.name = name;
        this.description = description;
        this.instructionFns = List.copyOf(instructionFns);
        this.tools = List.copyOf(tools);
        this.routes = List.copyOf(routes);
        this.dynamicRoutes = dynamicRoutes;
        this.inputGuards = List.copyOf(inputGuards);
        this.outputGuards = List.copyOf(outputGuards);
        this.outputType = outputType;
        this.outputSchema = outputSchema;
        this.temperature = temperature;
        this.maxTurns = maxTurns;
        this.toolChoice = toolChoice;
        this.dynamicToolChoice = dynamicToolChoice;
        this.parallelToolCalls = parallelToolCalls;
        this.contextType = contextType;
    }

    public String model() { return model; }
    public String name() { return name; }
    public String description() { return description; }
    public Class<T> contextType() { return contextType; }
    public Class<? extends Record> outputType() { return outputType; }
    public SchemaNode outputSchema() { return outputSchema; }
    public List<Tool> tools() { return tools; }
    public List<Agent<T>> routes() { return routes; }
    public Function<T, List<Agent<T>>> dynamicRoutes() { return dynamicRoutes; }
    public List<Guard<T>> inputGuards() { return inputGuards; }
    public List<Guard<T>> outputGuards() { return outputGuards; }
    public Double temperature() { return temperature; }
    public Integer maxTurns() { return maxTurns; }
    public ToolChoice toolChoice() { return toolChoice; }
    public Function<T, ToolChoice> dynamicToolChoice() { return dynamicToolChoice; }
    public Boolean parallelToolCalls() { return parallelToolCalls; }
    public List<Function<T, String>> instructionFns() { return instructionFns; }

    /** Render the instructions for a given context. */
    public String renderInstructions(T context) {
        if (instructionFns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(256);
        for (var fn : instructionFns) {
            String piece = fn.apply(context);
            if (piece == null || piece.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(piece);
        }
        return sb.toString();
    }

    /**
     * Wrap this agent as a Tool for delegation. The tool name defaults to this agent's name.
     * The delegate runs its own loop with its own guards, tools, {@code toolChoice},
     * {@code parallelToolCalls}, and {@code maxTurns}; parent settings do not propagate.
     */
    public Tool asTool(String description) {
        return asTool(name == null ? "agent" : name, description, null);
    }

    /** Wrap as a Tool with a custom tool name (to avoid collisions with other tools). */
    public Tool asTool(String toolName, String description) {
        return asTool(toolName, description, null);
    }

    /**
     * Wrap as a Tool with a custom tool name and a {@code outputExtractor} that post-processes
     * the delegate's {@link Reply} into the string returned to the parent LLM. If null, the
     * delegate's {@code reply.text()} (or structured output, as a nested JSON object) is used.
     */
    public Tool asTool(String toolName, String description, Function<Reply, String> outputExtractor) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(description, "description");
        ToolInvoker invoker = (ctx, argsJson) -> {
            throw new UnsupportedOperationException(
                    "Agent-as-tool delegation is dispatched by Kite.run; the invoker is never called directly");
        };
        SchemaNode schema = new SchemaNode.Obj(
                Map.of("input", new SchemaNode.Str(
                        "Prompt/task to send to the " + toolName + " sub-agent", null)),
                List.of("input"),
                description,
                true);
        return Tool.delegate(toolName, description, schema, invoker, this, outputExtractor);
    }

    /** Start building a no-context (Void) agent. Model is set via {@link AgentBuilder#model(String)}. */
    public static AgentBuilder<Void> builder() {
        return new AgentBuilder<>(Void.class);
    }

    /** Start building an agent with the given typed context. Model is set via {@link AgentBuilder#model(String)}. */
    public static <T> AgentBuilder<T> builder(Class<T> contextType) {
        return new AgentBuilder<>(contextType);
    }
}
