package io.kite.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import io.kite.internal.json.JsonCodec;
import io.kite.model.ChatChunk;
import io.kite.model.Usage;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Translates Anthropic streaming SSE events into Kite {@link ChatChunk}s. One instance per stream.
 *
 * <p>Anthropic event sequence for a typical turn:
 * <pre>
 * message_start              — initial usage info
 * content_block_start        — a new text block or tool_use block begins
 *   content_block_delta      — text_delta or input_json_delta fragments
 * content_block_stop         — the block ends
 * (repeat content_block_* for each block)
 * message_delta              — final usage update
 * message_stop               — terminal
 * </pre>
 */
public final class AnthropicStreamDispatcher {

    private final JsonCodec json;
    private final Consumer<ChatChunk> out;
    private final Map<Integer, BlockState> blocks = new HashMap<>();
    private long inputTokens;
    private long outputTokens;

    public AnthropicStreamDispatcher(JsonCodec json, Consumer<ChatChunk> out) {
        this.json = json;
        this.out = out;
    }

    public void onSseEvent(String eventName, String data) {
        if (eventName == null) return;
        switch (eventName) {
            case "message_start" -> onMessageStart(data);
            case "content_block_start" -> onBlockStart(data);
            case "content_block_delta" -> onBlockDelta(data);
            case "content_block_stop" -> onBlockStop(data);
            case "message_delta" -> onMessageDelta(data);
            case "message_stop" -> out.accept(new ChatChunk.Done(new Usage(inputTokens, outputTokens, inputTokens + outputTokens, 0.0), "end_turn"));
            case "error" -> {
                JsonNode n = json.readTree(data);
                JsonNode err = n.get("error");
                String msg = err != null ? textField(err, "message") : textField(n, "message");
                out.accept(new ChatChunk.Error(msg == null ? "Anthropic stream error" : msg, null));
            }
            case "ping" -> { /* ignore */ }
            default -> { /* ignore unknown */ }
        }
    }

    private void onMessageStart(String data) {
        JsonNode n = json.readTree(data);
        JsonNode msg = n.get("message");
        if (msg == null) return;
        JsonNode usage = msg.get("usage");
        if (usage != null) {
            if (usage.has("input_tokens")) inputTokens = usage.get("input_tokens").asLong();
            if (usage.has("output_tokens")) outputTokens = usage.get("output_tokens").asLong();
        }
    }

    private void onBlockStart(String data) {
        JsonNode n = json.readTree(data);
        int index = n.has("index") ? n.get("index").asInt() : 0;
        JsonNode block = n.get("content_block");
        if (block == null) return;
        String type = textField(block, "type");
        if ("tool_use".equals(type)) {
            String id = textField(block, "id");
            String name = textField(block, "name");
            blocks.put(index, new BlockState(BlockKind.TOOL_USE, id, name));
            out.accept(new ChatChunk.ToolCallStart(index, id, name));
        } else if ("text".equals(type)) {
            blocks.put(index, new BlockState(BlockKind.TEXT, null, null));
        }
    }

    private void onBlockDelta(String data) {
        JsonNode n = json.readTree(data);
        int index = n.has("index") ? n.get("index").asInt() : 0;
        JsonNode delta = n.get("delta");
        if (delta == null) return;
        String type = textField(delta, "type");
        if ("text_delta".equals(type)) {
            String text = textField(delta, "text");
            if (text != null && !text.isEmpty()) out.accept(new ChatChunk.TextDelta(text));
        } else if ("input_json_delta".equals(type)) {
            String partial = textField(delta, "partial_json");
            if (partial == null) return;
            BlockState state = blocks.get(index);
            if (state == null || state.kind != BlockKind.TOOL_USE) return;
            state.argsBuffer.append(partial);
            out.accept(new ChatChunk.ToolCallDelta(index, partial));
        }
    }

    private void onBlockStop(String data) {
        JsonNode n = json.readTree(data);
        int index = n.has("index") ? n.get("index").asInt() : 0;
        BlockState state = blocks.remove(index);
        if (state == null || state.kind != BlockKind.TOOL_USE) return;
        out.accept(new ChatChunk.ToolCallComplete(
                index,
                state.id,
                state.name,
                state.argsBuffer.length() == 0 ? "{}" : state.argsBuffer.toString()));
    }

    private void onMessageDelta(String data) {
        JsonNode n = json.readTree(data);
        JsonNode usage = n.get("usage");
        if (usage != null && usage.has("output_tokens")) {
            outputTokens = usage.get("output_tokens").asLong();
        }
    }

    private static String textField(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode f = node.get(field);
        if (f == null || f.isNull()) return null;
        return f.asText();
    }

    private enum BlockKind { TEXT, TOOL_USE }

    private static final class BlockState {
        final BlockKind kind;
        final String id;
        final String name;
        final StringBuilder argsBuffer = new StringBuilder(128);

        BlockState(BlockKind kind, String id, String name) {
            this.kind = kind;
            this.id = id;
            this.name = name;
        }
    }
}
