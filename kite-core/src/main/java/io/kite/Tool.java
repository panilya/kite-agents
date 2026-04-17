package io.kite;

import io.kite.internal.runtime.ToolInvoker;
import io.kite.schema.SchemaNode;

import java.util.Objects;
import java.util.function.Function;

/**
 * A single callable tool exposed to the LLM. Instances are immutable and created either by
 * scanning {@code @Tool}-annotated methods via {@link AgentBuilder#tools(Object)} or by the
 * programmatic {@link #create(String)} builder.
 *
 * <p>Tools are also used for routing: {@link Agent#asTool(String)} wraps an agent as a tool,
 * and Kite auto-generates synthetic tools for {@code .route(otherAgent)} calls.
 */
public final class Tool {

    private final String name;
    private final String description;
    private final SchemaNode paramsSchema;
    private final String paramsSchemaJson;
    private final ToolInvoker invoker;
    private final boolean usesContext;
    private final Kind kind;
    private final Agent<?> routeTarget;   // non-null when kind == ROUTE or kind == DELEGATE
    private final Function<Reply, String> outputExtractor;   // non-null only for DELEGATE with custom extractor
    private final boolean readOnly;

    public enum Kind { FUNCTION, ROUTE, DELEGATE }

    /**
     * Internal constructor. External users should use {@link #create(String)} or the
     * {@code @Tool} annotation on a POJO method passed to {@code AgentBuilder.tools(bean)}.
     * Runtime callers in {@code io.kite.internal.runtime} use {@link Tools#newFunctionTool}
     * instead of invoking this directly.
     *
     * <p>{@code readOnly} declares that the tool has no externally-observable side effects.
     * Kite may start it in parallel with any still-running input guards instead of waiting
     * for them to finish, and will simply throw the result away if a guard blocks. Only
     * meaningful for {@link Kind#FUNCTION}; {@link Kind#ROUTE} and {@link Kind#DELEGATE}
     * always run after guards resolve and must pass {@code false}.
     */
    Tool(String name,
         String description,
         SchemaNode paramsSchema,
         ToolInvoker invoker,
         boolean usesContext,
         Kind kind,
         Agent<?> routeTarget,
         Function<Reply, String> outputExtractor,
         boolean readOnly) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.paramsSchema = paramsSchema;
        this.paramsSchemaJson = paramsSchema == null ? "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}" : paramsSchema.writeJson();
        this.invoker = Objects.requireNonNull(invoker, "invoker");
        this.usesContext = usesContext;
        this.kind = kind;
        this.routeTarget = routeTarget;
        this.outputExtractor = outputExtractor;
        if (readOnly && kind != Kind.FUNCTION) {
            throw new IllegalArgumentException(
                    "Tool '" + name + "' kind=" + kind + " cannot be readOnly — only FUNCTION tools support speculative execution");
        }
        this.readOnly = readOnly;
    }

    public String name() { return name; }
    public String description() { return description; }
    public SchemaNode paramsSchema() { return paramsSchema; }
    public String paramsSchemaJson() { return paramsSchemaJson; }
    public boolean usesContext() { return usesContext; }
    public Kind kind() { return kind; }
    public Agent<?> routeTarget() { return routeTarget; }
    public Function<Reply, String> outputExtractor() { return outputExtractor; }
    public boolean readOnly() { return readOnly; }

    /** Internal accessor — invokes the underlying function. */
    public ToolInvoker invoker() { return invoker; }

    /** Start building a programmatic Tool. */
    public static ToolBuilder create(String name) {
        return new ToolBuilder(name);
    }
}
