package io.kite.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.kite.internal.json.JsonCodec;
import io.kite.model.ChatChunk;
import io.kite.model.Usage;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Translates semantic SSE events from {@code /v1/responses} into Kite {@link ChatChunk}s.
 * One instance per stream; not thread-safe.
 *
 * <p>Handles the minimal set of event types Kite needs:
 * <ul>
 *   <li>{@code response.output_text.delta} → {@link ChatChunk.TextDelta}</li>
 *   <li>{@code response.output_item.added} (type=function_call) → {@link ChatChunk.ToolCallStart}</li>
 *   <li>{@code response.function_call_arguments.delta} → {@link ChatChunk.ToolCallDelta}</li>
 *   <li>{@code response.function_call_arguments.done} → {@link ChatChunk.ToolCallComplete}</li>
 *   <li>{@code response.completed} → {@link ChatChunk.Done} with usage</li>
 *   <li>{@code error} → {@link ChatChunk.Error}</li>
 * </ul>
 *
 * <p>All other event types (reasoning, file_search, web_search, image_generation, MCP tool
 * lifecycle, content_part.added/done, output_item.done, etc.) are intentionally ignored.
 */
public final class OpenAiResponsesStreamDispatcher {

    private final JsonCodec json;
    private final Consumer<ChatChunk> out;
    private final Map<Integer, PendingCall> pendingCalls = new HashMap<>();

    public OpenAiResponsesStreamDispatcher(JsonCodec json, Consumer<ChatChunk> out) {
        this.json = json;
        this.out = out;
    }

    public void onSseEvent(String eventName, String data) {
        if (eventName == null) return;
        switch (eventName) {
            case "response.created" -> { /* no-op */ }
            case "response.output_item.added" -> onOutputItemAdded(data);
            case "response.content_part.added" -> { /* no-op */ }
            case "response.output_text.delta" -> {
                JsonNode n = json.readTree(data);
                String delta = textField(n, "delta");
                if (delta != null && !delta.isEmpty()) out.accept(new ChatChunk.TextDelta(delta));
            }
            case "response.output_text.done" -> { /* no-op — text already streamed */ }
            case "response.function_call_arguments.delta" -> onFunctionArgsDelta(data);
            case "response.function_call_arguments.done" -> onFunctionArgsDone(data);
            case "response.output_item.done" -> { /* no-op */ }
            case "response.completed" -> onCompleted(data);
            case "error" -> {
                JsonNode n = json.readTree(data);
                String msg = textField(n, "message");
                if (msg == null) msg = "unknown error";
                out.accept(new ChatChunk.Error(msg, null));
            }
            default -> { /* ignore the other ~40 event types */ }
        }
    }

    private void onOutputItemAdded(String data) {
        JsonNode n = json.readTree(data);
        JsonNode item = n.get("item");
        if (item == null) return;
        String type = textField(item, "type");
        if (!"function_call".equals(type)) return;
        int index = n.has("output_index") ? n.get("output_index").asInt() : 0;
        String id = textField(item, "call_id");
        String name = textField(item, "name");
        if (id == null) id = textField(item, "id");
        pendingCalls.put(index, new PendingCall(id, name));
        out.accept(new ChatChunk.ToolCallStart(index, id, name));
    }

    private void onFunctionArgsDelta(String data) {
        JsonNode n = json.readTree(data);
        int index = n.has("output_index") ? n.get("output_index").asInt() : 0;
        String delta = textField(n, "delta");
        if (delta == null || delta.isEmpty()) return;
        PendingCall pc = pendingCalls.computeIfAbsent(index, k -> new PendingCall(null, null));
        pc.args.append(delta);
        out.accept(new ChatChunk.ToolCallDelta(index, delta));
    }

    private void onFunctionArgsDone(String data) {
        JsonNode n = json.readTree(data);
        int index = n.has("output_index") ? n.get("output_index").asInt() : 0;
        PendingCall pc = pendingCalls.remove(index);
        if (pc == null) return;
        // Prefer the canonical "arguments" field if the server provides it; otherwise use accumulated fragments.
        String finalArgs = textField(n, "arguments");
        if (finalArgs == null || finalArgs.isEmpty()) finalArgs = pc.args.toString();
        out.accept(new ChatChunk.ToolCallComplete(index, pc.id, pc.name, finalArgs));
    }

    private void onCompleted(String data) {
        JsonNode n = json.readTree(data);
        JsonNode resp = n.get("response");
        Usage usage = resp == null ? Usage.ZERO : OpenAiResponsesParser.parseUsage(resp.get("usage"));
        String stopReason = resp == null ? "completed" : textField(resp, "status");
        out.accept(new ChatChunk.Done(usage, stopReason == null ? "completed" : stopReason));
    }

    private static String textField(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode f = node.get(field);
        if (f == null || f.isNull()) return null;
        return f.asText();
    }

    private static final class PendingCall {
        String id;
        String name;
        final StringBuilder args = new StringBuilder(128);
        PendingCall(String id, String name) { this.id = id; this.name = name; }
    }
}
