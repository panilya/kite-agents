package io.kite.openai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kite.ToolChoice;
import io.kite.internal.json.JsonCodec;
import io.kite.model.ChatRequest;
import io.kite.model.Message;
import io.kite.openai.schema.OpenAiSchemaAdapter;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Builds the JSON request body for {@code POST /v1/responses}.
 *
 * <p>Responses API specifics:
 * <ul>
 *   <li>{@code instructions} is a top-level field (no system message in {@code input}).</li>
 *   <li>{@code input} is an ordered list of items: user/assistant messages and
 *       {@code function_call} / {@code function_call_output} items for prior tool use.</li>
 *   <li>{@code tools} uses the flat shape {@code {type:"function", name, description, parameters}}
 *       — no nested {@code function:} wrapper like Chat Completions requires.</li>
 *   <li>Structured outputs go through {@code text.format = {type:"json_schema", ...}}.</li>
 *   <li>{@code store: false} disables server-side conversation storage; Kite manages history itself.</li>
 * </ul>
 */
public final class OpenAiResponsesSerializer {

    private OpenAiResponsesSerializer() {}

    public static String serialize(ChatRequest req, boolean stream) {
        requirePairedToolCalls(req.messages());

        JsonCodec codec = JsonCodec.shared();
        ObjectNode root = codec.mapper().createObjectNode();
        root.put("model", req.model());
        if (req.instructions() != null && !req.instructions().isEmpty()) {
            root.put("instructions", req.instructions());
        }

        ArrayNode input = root.putArray("input");
        for (Message m : req.messages()) {
            switch (m) {
                case Message.System s -> {
                    // Responses API uses top-level 'instructions' for system text — but if an
                    // earlier turn included a system message in history, still serialize it as
                    // a developer message for safety.
                    ObjectNode item = input.addObject();
                    item.put("role", "developer");
                    item.put("content", s.content());
                }
                case Message.User u -> {
                    ObjectNode item = input.addObject();
                    item.put("role", "user");
                    item.put("content", u.content());
                }
                case Message.Assistant a -> {
                    if (a.content() != null && !a.content().isEmpty()) {
                        ObjectNode item = input.addObject();
                        item.put("role", "assistant");
                        item.put("content", a.content());
                    }
                    for (var call : a.toolCalls()) {
                        ObjectNode fc = input.addObject();
                        fc.put("type", "function_call");
                        fc.put("call_id", call.id());
                        fc.put("name", call.name());
                        fc.put("arguments", call.argsJson() == null ? "{}" : call.argsJson());
                    }
                }
                case Message.Tool t -> {
                    ObjectNode item = input.addObject();
                    item.put("type", "function_call_output");
                    item.put("call_id", t.toolCallId());
                    item.put("output", t.resultJson() == null ? "" : t.resultJson());
                }
            }
        }

        if (!req.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (var t : req.tools()) {
                ObjectNode tn = tools.addObject();
                tn.put("type", "function");
                tn.put("name", t.name());
                if (t.description() != null && !t.description().isEmpty()) {
                    tn.put("description", t.description());
                }
                tn.put("strict", true);
                tn.set("parameters", OpenAiSchemaAdapter.toStrict(t.paramsSchema()).toJackson());
            }
        }

        if (req.toolChoice() != null) {
            switch (req.toolChoice()) {
                case ToolChoice.Auto a     -> root.put("tool_choice", "auto");
                case ToolChoice.None n     -> root.put("tool_choice", "none");
                case ToolChoice.Required r -> root.put("tool_choice", "required");
                case ToolChoice.Specific s -> {
                    ObjectNode tc = root.putObject("tool_choice");
                    tc.put("type", "function");
                    tc.put("name", s.name());
                }
            }
        }
        if (req.parallelToolCalls() != null) {
            root.put("parallel_tool_calls", req.parallelToolCalls());
        }

        if (req.outputSchema() != null) {
            ObjectNode text = root.putObject("text");
            ObjectNode format = text.putObject("format");
            format.put("type", "json_schema");
            format.put("name", req.outputName() == null ? "Output" : req.outputName());
            format.put("strict", true);
            format.set("schema", OpenAiSchemaAdapter.toStrict(req.outputSchema()).toJackson());
        }

        if (req.temperature() != null) root.put("temperature", req.temperature());
        if (req.maxTokens() != null) root.put("max_output_tokens", req.maxTokens());
        root.put("stream", stream);
        root.put("store", false);

        return codec.writeValueAsString(root);
    }

    /**
     * Fail fast on unpaired function_calls — Responses API requires every function_call in
     * input to have a matching function_call_output. Catches Runner bugs before the network
     * round-trip so the stack trace points at the real origin.
     */
    private static void requirePairedToolCalls(List<Message> messages) {
        LinkedHashMap<String, String> unpaired = new LinkedHashMap<>();
        for (Message m : messages) {
            if (m instanceof Message.Assistant a) {
                for (var call : a.toolCalls()) unpaired.put(call.id(), call.name());
            } else if (m instanceof Message.Tool t) {
                unpaired.remove(t.toolCallId());
            }
        }
        if (!unpaired.isEmpty()) {
            throw new IllegalStateException(
                    "OpenAiResponsesSerializer: " + unpaired.size()
                            + " function_call(s) without matching function_call_output: " + unpaired);
        }
    }
}
