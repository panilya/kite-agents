package io.kite;

import io.kite.internal.runtime.ToolInvoker;
import io.kite.schema.SchemaNode;

import java.util.List;
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
    final List<Guard<T>> beforeGuards;
    final List<Guard<T>> afterGuards;
    final Class<?> outputType;           // nullable; must be a record
    final SchemaNode outputSchema;       // nullable; built from outputType at build time
    final Double temperature;
    final Integer maxTurns;
    final ToolChoice toolChoice;                          // nullable — provider default when null
    final Function<T, ToolChoice> dynamicToolChoice;      // nullable — alternative to static
    final Boolean parallelToolCalls;                      // nullable — provider default when null
    final Class<T> contextType;          // never null — Void.class for no-context agents

    Agent(String model,
          String name,
          String description,
          List<Function<T, String>> instructionFns,
          List<Tool> tools,
          List<Agent<T>> routes,
          Function<T, List<Agent<T>>> dynamicRoutes,
          List<Guard<T>> beforeGuards,
          List<Guard<T>> afterGuards,
          Class<?> outputType,
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
        this.beforeGuards = List.copyOf(beforeGuards);
        this.afterGuards = List.copyOf(afterGuards);
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
    public Class<?> outputType() { return outputType; }
    public SchemaNode outputSchema() { return outputSchema; }
    public List<Tool> tools() { return tools; }
    public List<Agent<T>> routes() { return routes; }
    public Function<T, List<Agent<T>>> dynamicRoutes() { return dynamicRoutes; }
    public List<Guard<T>> beforeGuards() { return beforeGuards; }
    public List<Guard<T>> afterGuards() { return afterGuards; }
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
     * Wrap this agent as a Tool for delegation from another agent. The delegated agent runs its
     * own loop with its own {@code toolChoice} and {@code parallelToolCalls} settings; the
     * parent's settings do not propagate through delegation.
     */
    public Tool asTool(String description) {
        ToolInvoker invoker = (ctx, argsJson) -> {
            throw new UnsupportedOperationException(
                    "Agent-as-tool delegation must be invoked through Kite.run; direct invocation is not supported");
        };
        return new Tool(name == null ? "agent" : name, description, null, invoker, false, Tool.Kind.DELEGATE, this);
    }

    /** Start building a no-context (Void) agent. */
    public static AgentBuilder<Void> of(String model) {
        return new AgentBuilder<>(model, Void.class);
    }

    /** Start building an agent with the given typed context. */
    public static <T> AgentBuilder<T> of(String model, Class<T> contextType) {
        return new AgentBuilder<>(model, contextType);
    }
}
