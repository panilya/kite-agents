package io.kite.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import io.kite.internal.json.JsonCodec;
import io.kite.model.ChatResponse;
import io.kite.model.Message;
import io.kite.model.Usage;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a non-streaming Messages API response into a Kite {@link ChatResponse}. Anthropic's
 * content is always a list of blocks — {@code text} blocks get concatenated into the assistant
 * text; {@code tool_use} blocks become tool call refs with their {@code input} re-serialized to JSON.
 */
public final class AnthropicMessageParser {

    private AnthropicMessageParser() {}

    public static ChatResponse parse(JsonNode root) {
        StringBuilder text = new StringBuilder();
        List<Message.ToolCallRef> calls = new ArrayList<>();

        JsonNode content = root.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                String type = textField(block, "type");
                if ("text".equals(type)) {
                    String t = textField(block, "text");
                    if (t != null) text.append(t);
                } else if ("tool_use".equals(type)) {
                    String id = textField(block, "id");
                    String name = textField(block, "name");
                    JsonNode input = block.get("input");
                    String argsJson = input == null ? "{}" : JsonCodec.shared().writeValueAsString(input);
                    calls.add(new Message.ToolCallRef(
                            id == null ? "call_" + calls.size() : id,
                            name == null ? "" : name,
                            argsJson));
                }
                // ignore: thinking, redacted_thinking
            }
        }

        Usage usage = parseUsage(root.get("usage"));
        String stopReason = textField(root, "stop_reason");
        String model = textField(root, "model");
        String id = textField(root, "id");
        return new ChatResponse(text.toString(), calls, usage, stopReason, model, id);
    }

    public static Usage parseUsage(JsonNode node) {
        if (node == null || node.isNull()) return Usage.ZERO;
        long in = node.has("input_tokens") ? node.get("input_tokens").asLong() : 0;
        long out = node.has("output_tokens") ? node.get("output_tokens").asLong() : 0;
        return new Usage(in, out, in + out, 0.0);
    }

    private static String textField(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode f = node.get(field);
        if (f == null || f.isNull()) return null;
        return f.asText();
    }
}
