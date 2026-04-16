package io.kite.anthropic;

import io.kite.internal.json.JsonCodec;
import io.kite.model.ChatChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicStreamDispatcherTest {

    @Test
    void textDeltaSequenceYieldsTextDeltas() {
        List<ChatChunk> chunks = new ArrayList<>();
        var d = new AnthropicStreamDispatcher(JsonCodec.shared(), chunks::add);
        d.onSseEvent("message_start", "{\"message\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":0}}}");
        d.onSseEvent("content_block_start", "{\"index\":0,\"content_block\":{\"type\":\"text\"}}");
        d.onSseEvent("content_block_delta", "{\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}");
        d.onSseEvent("content_block_delta", "{\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\", world\"}}");
        d.onSseEvent("content_block_stop", "{\"index\":0}");
        d.onSseEvent("message_delta", "{\"usage\":{\"output_tokens\":5}}");
        d.onSseEvent("message_stop", "{}");

        long deltas = chunks.stream().filter(c -> c instanceof ChatChunk.TextDelta).count();
        assertThat(deltas).isEqualTo(2);
        ChatChunk last = chunks.get(chunks.size() - 1);
        assertThat(last).isInstanceOf(ChatChunk.Done.class);
        assertThat(((ChatChunk.Done) last).usage().completionTokens()).isEqualTo(5);
    }

    @Test
    void toolUseBlockYieldsStartDeltaComplete() {
        List<ChatChunk> chunks = new ArrayList<>();
        var d = new AnthropicStreamDispatcher(JsonCodec.shared(), chunks::add);
        d.onSseEvent("content_block_start",
                "{\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"refund\"}}");
        d.onSseEvent("content_block_delta",
                "{\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"id\\\":\"}}");
        d.onSseEvent("content_block_delta",
                "{\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"99}\"}}");
        d.onSseEvent("content_block_stop", "{\"index\":0}");

        var start = chunks.stream().filter(c -> c instanceof ChatChunk.ToolCallStart).findFirst().orElseThrow();
        var complete = chunks.stream().filter(c -> c instanceof ChatChunk.ToolCallComplete).findFirst().orElseThrow();
        assertThat(((ChatChunk.ToolCallStart) start).name()).isEqualTo("refund");
        assertThat(((ChatChunk.ToolCallComplete) complete).argsJson()).isEqualTo("{\"id\":99}");
    }
}
