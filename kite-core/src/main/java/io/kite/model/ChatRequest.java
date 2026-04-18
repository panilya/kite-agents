package io.kite.model;

import io.kite.ToolChoice;
import io.kite.schema.SchemaNode;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral chat request. Each ModelProvider converts this into its own wire format.
 */
public record ChatRequest(
        String model,
        String instructions,
        List<Message> messages,
        List<ToolSchema> tools,
        ToolChoice toolChoice,         // nullable — provider default when null
        Boolean parallelToolCalls,     // nullable — provider default when null
        SchemaNode outputSchema,       // nullable — structured output schema
        String outputName,             // nullable — name for the schema (e.g. record simple name)
        Double temperature,            // nullable — provider default when null
        Integer maxTokens,             // nullable — no cap when null
        boolean stream) {

    public ChatRequest {
        Objects.requireNonNull(model, "model");
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * Description of one tool the model may call. The {@code paramsSchema} is the provider-agnostic
     * IR; each provider's serializer applies its own adapter and emits its own wire shape.
     */
    public record ToolSchema(String name, String description, SchemaNode paramsSchema) {}
}
