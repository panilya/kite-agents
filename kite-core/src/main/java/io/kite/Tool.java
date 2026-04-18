package io.kite;

import io.kite.internal.runtime.ToolInvoker;
import io.kite.schema.SchemaNode;

import java.util.Objects;
import java.util.function.Function;

/**
 * A single callable tool exposed to the LLM. Instances are immutable and created either by
 * scanning {@code @Tool}-annotated methods via {@link AgentBuilder#tools(Object)} or by the
 * programmatic {@link #create(String)} builder. {@link Agent#asTool(String)} wraps an agent
 * as a delegate tool.
 */
public final class Tool {

    public static final String EMPTY_PARAMS_SCHEMA_JSON =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    private final String name;
    private final String description;
    private final SchemaNode paramsSchema;
    private final String paramsSchemaJson;
    private final ToolInvoker invoker;
    private final boolean usesContext;
    private final Kind kind;
    private final Agent<?> delegateTarget;   // non-null when kind == DELEGATE
    private final Function<Reply, String> outputExtractor;   // non-null only for DELEGATE with custom extractor
    private final boolean readOnly;

    public enum Kind { FUNCTION, DELEGATE }

    private Tool(String name,
                 String description,
                 SchemaNode paramsSchema,
                 ToolInvoker invoker,
                 boolean usesContext,
                 Kind kind,
                 Agent<?> delegateTarget,
                 Function<Reply, String> outputExtractor,
                 boolean readOnly) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.paramsSchema = paramsSchema;
        this.paramsSchemaJson = paramsSchema == null ? EMPTY_PARAMS_SCHEMA_JSON : paramsSchema.writeJson();
        this.invoker = Objects.requireNonNull(invoker, "invoker");
        this.usesContext = usesContext;
        this.kind = kind;
        this.delegateTarget = delegateTarget;
        this.outputExtractor = outputExtractor;
        this.readOnly = readOnly;
    }

    /**
     * Build a plain function tool. {@code readOnly} declares the tool has no externally-observable
     * side effects; Kite may start it in parallel with still-running input guards and discard the
     * result if a guard blocks.
     */
    public static Tool function(String name,
                                String description,
                                SchemaNode paramsSchema,
                                ToolInvoker invoker,
                                boolean usesContext,
                                boolean readOnly) {
        return new Tool(name, description, paramsSchema, invoker, usesContext, Kind.FUNCTION, null, null, readOnly);
    }

    /**
     * Build a delegate tool that hands the call off to a sub-agent. Delegation always runs after
     * input guards resolve, so speculative execution ({@code readOnly}) does not apply.
     */
    public static Tool delegate(String name,
                                String description,
                                SchemaNode paramsSchema,
                                ToolInvoker invoker,
                                Agent<?> target,
                                Function<Reply, String> outputExtractor) {
        Objects.requireNonNull(target, "target");
        return new Tool(name, description, paramsSchema, invoker, false, Kind.DELEGATE, target, outputExtractor, false);
    }

    public String name() { return name; }
    public String description() { return description; }
    public SchemaNode paramsSchema() { return paramsSchema; }
    public String paramsSchemaJson() { return paramsSchemaJson; }
    public boolean usesContext() { return usesContext; }
    public Kind kind() { return kind; }
    public Agent<?> routeTarget() { return delegateTarget; }
    public Function<Reply, String> outputExtractor() { return outputExtractor; }
    public boolean readOnly() { return readOnly; }

    /** Internal accessor — invokes the underlying function. */
    public ToolInvoker invoker() { return invoker; }

    /** Start building a programmatic Tool. */
    public static ToolBuilder create(String name) {
        return new ToolBuilder(name);
    }
}
