package io.kite;

import io.kite.internal.runtime.ToolInvoker;
import io.kite.schema.SchemaNode;

/**
 * Internal accessor that lets {@code io.kite.internal.runtime} construct {@link Tool} instances
 * without exposing the package-private constructor on the public API. Not for application use —
 * use {@link Tool#create(String)} or the {@code @Tool} annotation instead.
 */
public final class Tools {
    private Tools() {}

    public static Tool newFunctionTool(String name,
                                       String description,
                                       SchemaNode paramsSchema,
                                       ToolInvoker invoker,
                                       boolean usesContext) {
        return new Tool(name, description, paramsSchema, invoker, usesContext, Tool.Kind.FUNCTION, null, null);
    }
}
