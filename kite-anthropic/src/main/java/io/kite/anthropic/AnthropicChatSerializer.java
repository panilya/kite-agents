package io.kite.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kite.ToolChoice;
import io.kite.internal.json.JsonCodec;
import io.kite.model.ChatRequest;
import io.kite.model.Message;

/**
 * Builds the JSON request body for {@code POST /v1/messages}.
 *
 * <p>Anthropic specifics:
 * <ul>
 *   <li>{@code system} is a top-level field, not a message role.</li>
 *   <li>There is no {@code tool} role — tool results are sent as a {@code user} message whose
 *       content is a list of {@code tool_result} content blocks.</li>
 *   <li>Assistant tool calls appear as {@code tool_use} content blocks inside an {@code assistant}
 *       message.</li>
 *   <li>Tools are declared with {@code input_schema} (not {@code parameters}).</li>
 *   <li>Structured outputs go through {@code output_config.format = {type:"json_schema", schema}}.</li>
 * </ul>
 */
public final class AnthropicChatSerializer {

    private static final int DEFAULT_MAX_TOKENS = 4096;

    private AnthropicChatSerializer() {}

    public static String serialize(ChatRequest req, boolean stream) {
        JsonCodec codec = JsonCodec.shared();
        ObjectNode root = codec.mapper().createObjectNode();
        root.put("model", req.model());
        root.put("max_tokens", req.maxTokens() == null ? DEFAULT_MAX_TOKENS : req.maxTokens());
        if (req.instructions() != null && !req.instructions().isEmpty()) {
            root.put("system", req.instructions());
        }
        if (req.temperature() != null) root.put("temperature", req.temperature());

        ArrayNode messages = root.putArray("messages");
        MessageBuffer pendingUserBuffer = new MessageBuffer("user", messages, codec);

        for (Message m : req.messages()) {
            switch (m) {
                case Message.System ignored -> {
                    // Skip — already handled via top-level 'system'. Earlier system messages in
                    // history shouldn't reach here under normal operation.
                }
                case Message.User u -> {
                    pendingUserBuffer.flush();
                    ObjectNode msg = messages.addObject();
                    msg.put("role", "user");
                    msg.put("content", u.content());
                }
                case Message.Assistant a -> {
                    pendingUserBuffer.flush();
                    ObjectNode msg = messages.addObject();
                    msg.put("role", "assistant");
                    ArrayNode contentArr = msg.putArray("content");
                    if (a.content() != null && !a.content().isEmpty()) {
                        ObjectNode textBlock = contentArr.addObject();
                        textBlock.put("type", "text");
                        textBlock.put("text", a.content());
                    }
                    for (var call : a.toolCalls()) {
                        ObjectNode useBlock = contentArr.addObject();
                        useBlock.put("type", "tool_use");
                        useBlock.put("id", call.id());
                        useBlock.put("name", call.name());
                        JsonNode inputNode = call.argsJson() == null || call.argsJson().isEmpty()
                                ? codec.mapper().createObjectNode()
                                : codec.readTree(call.argsJson());
                        useBlock.set("input", inputNode);
                    }
                }
                case Message.Tool t -> {
                    // Tool results are user-role messages with tool_result content blocks.
                    // Batch consecutive tool results into a single user message.
                    pendingUserBuffer.addToolResult(t.toolCallId(), t.resultJson());
                }
            }
        }
        pendingUserBuffer.flush();

        if (!req.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (var t : req.tools()) {
                ObjectNode tn = tools.addObject();
                tn.put("name", t.name());
                if (t.description() != null && !t.description().isEmpty()) {
                    tn.put("description", t.description());
                }
                tn.set("input_schema", codec.readTree(t.paramsSchemaJson()));
            }
        }

        // Anthropic fuses the parallel-tool-calls toggle INSIDE tool_choice as
        // `disable_parallel_tool_use`, so the two Kite-level fields collapse into one object.
        // Only emit `disable_parallel_tool_use: true` — never the redundant `false`.
        boolean hasChoice = req.toolChoice() != null;
        boolean disableParallel = Boolean.FALSE.equals(req.parallelToolCalls());
        if (hasChoice || disableParallel) {
            ObjectNode tc = root.putObject("tool_choice");
            if (!hasChoice) {
                tc.put("type", "auto");
            } else {
                switch (req.toolChoice()) {
                    case ToolChoice.Auto a     -> tc.put("type", "auto");
                    case ToolChoice.None n     -> tc.put("type", "none");
                    case ToolChoice.Required r -> tc.put("type", "any");
                    case ToolChoice.Specific s -> {
                        tc.put("type", "tool");
                        tc.put("name", s.name());
                    }
                }
            }
            if (disableParallel) {
                tc.put("disable_parallel_tool_use", true);
            }
        }

        if (req.outputSchema() != null) {
            ObjectNode outputConfig = root.putObject("output_config");
            ObjectNode format = outputConfig.putObject("format");
            format.put("type", "json_schema");
            format.set("schema", req.outputSchema().toJackson());
        }

        root.put("stream", stream);

        return codec.writeValueAsString(root);
    }

    /** Accumulates consecutive tool results into a single user message. */
    private static final class MessageBuffer {
        private final String role;
        private final ArrayNode parentMessages;
        private final JsonCodec codec;
        private ObjectNode pendingMessage;
        private ArrayNode pendingContent;

        MessageBuffer(String role, ArrayNode parentMessages, JsonCodec codec) {
            this.role = role;
            this.parentMessages = parentMessages;
            this.codec = codec;
        }

        void addToolResult(String callId, String resultJson) {
            if (pendingMessage == null) {
                pendingMessage = parentMessages.addObject();
                pendingMessage.put("role", role);
                pendingContent = pendingMessage.putArray("content");
            }
            ObjectNode block = pendingContent.addObject();
            block.put("type", "tool_result");
            block.put("tool_use_id", callId);
            block.put("content", resultJson == null ? "" : resultJson);
        }

        void flush() {
            pendingMessage = null;
            pendingContent = null;
        }
    }
}
