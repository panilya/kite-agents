package io.kite;

import io.kite.internal.runtime.ToolInvoker;
import io.kite.schema.SchemaNode;

import java.util.Objects;

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
    private final Agent<?> routeTarget;   // non-null when kind == ROUTE

    public enum Kind { FUNCTION, ROUTE, DELEGATE }

    /**
     * Internal constructor. External users should use {@link #create(String)} or the
     * {@code @Tool} annotation on a POJO method passed to {@code AgentBuilder.tools(bean)}.
     */
    public Tool(String name,
                String description,
                SchemaNode paramsSchema,
                ToolInvoker invoker,
                boolean usesContext,
                Kind kind,
                Agent<?> routeTarget) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.paramsSchema = paramsSchema;
        this.paramsSchemaJson = paramsSchema == null ? "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}" : paramsSchema.writeJson();
        this.invoker = Objects.requireNonNull(invoker, "invoker");
        this.usesContext = usesContext;
        this.kind = kind;
        this.routeTarget = routeTarget;
    }

    public String name() { return name; }
    public String description() { return description; }
    public SchemaNode paramsSchema() { return paramsSchema; }
    public String paramsSchemaJson() { return paramsSchemaJson; }
    public boolean usesContext() { return usesContext; }
    public Kind kind() { return kind; }
    public Agent<?> routeTarget() { return routeTarget; }

    /** Internal accessor — invokes the underlying function. */
    public ToolInvoker invoker() { return invoker; }

    /** Start building a programmatic Tool. */
    public static ToolBuilder create(String name) {
        return new ToolBuilder(name);
    }
}
