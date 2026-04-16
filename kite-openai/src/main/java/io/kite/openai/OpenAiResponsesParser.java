package io.kite.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.kite.model.ChatResponse;
import io.kite.model.Message;
import io.kite.model.Usage;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a non-streaming Responses API response into a Kite {@link ChatResponse}. Walks the
 * top-level {@code output[]} array once: {@code message} items contribute their {@code output_text}
 * content parts to the assistant text, and {@code function_call} items become tool call refs.
 * Reasoning items are ignored in v1.
 */
public final class OpenAiResponsesParser {

    private OpenAiResponsesParser() {}

    public static ChatResponse parse(JsonNode root) {
        StringBuilder text = new StringBuilder();
        List<Message.ToolCallRef> calls = new ArrayList<>();

        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                String type = textField(item, "type");
                if ("message".equals(type)) {
                    JsonNode content = item.get("content");
                    if (content != null && content.isArray()) {
                        for (JsonNode part : content) {
                            String partType = textField(part, "type");
                            if ("output_text".equals(partType)) {
                                String piece = textField(part, "text");
                                if (piece != null) text.append(piece);
                            }
                        }
                    }
                } else if ("function_call".equals(type)) {
                    String callId = textField(item, "call_id");
                    String name = textField(item, "name");
                    String args = textField(item, "arguments");
                    if (callId == null || callId.isEmpty()) callId = textField(item, "id");
                    calls.add(new Message.ToolCallRef(
                            callId == null ? "call_" + calls.size() : callId,
                            name == null ? "" : name,
                            args == null ? "{}" : args));
                }
                // ignore: reasoning, web_search_call, file_search_call, image_generation_call, ...
            }
        }

        Usage usage = parseUsage(root.get("usage"));
        String stopReason = textField(root, "status");
        String model = textField(root, "model");
        String id = textField(root, "id");
        return new ChatResponse(text.toString(), calls, usage, stopReason, model, id);
    }

    public static Usage parseUsage(JsonNode node) {
        if (node == null || node.isNull()) return Usage.ZERO;
        long in = node.has("input_tokens") ? node.get("input_tokens").asLong() : 0;
        long out = node.has("output_tokens") ? node.get("output_tokens").asLong() : 0;
        long total = node.has("total_tokens") ? node.get("total_tokens").asLong() : in + out;
        return new Usage(in, out, total, 0.0);
    }

    private static String textField(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode f = node.get(field);
        if (f == null || f.isNull()) return null;
        return f.asText();
    }
}
