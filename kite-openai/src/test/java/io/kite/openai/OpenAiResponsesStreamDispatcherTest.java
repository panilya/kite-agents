package io.kite.openai;

import io.kite.internal.json.JsonCodec;
import io.kite.model.ChatChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiResponsesStreamDispatcherTest {

    @Test
    void textDeltasAreEmittedInOrder() {
        List<ChatChunk> chunks = new ArrayList<>();
        var d = new OpenAiResponsesStreamDispatcher(JsonCodec.shared(), chunks::add);
        d.onSseEvent("response.created", "{\"response\":{\"id\":\"resp_1\"}}");
        d.onSseEvent("response.output_text.delta", "{\"delta\":\"Hello\"}");
        d.onSseEvent("response.output_text.delta", "{\"delta\":\", \"}");
        d.onSseEvent("response.output_text.delta", "{\"delta\":\"world\"}");
        d.onSseEvent("response.completed", "{\"response\":{\"usage\":{\"input_tokens\":5,\"output_tokens\":3,\"total_tokens\":8},\"status\":\"completed\"}}");

        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0)).isInstanceOf(ChatChunk.TextDelta.class);
        assertThat(((ChatChunk.TextDelta) chunks.get(0)).text()).isEqualTo("Hello");
        assertThat(chunks.get(3)).isInstanceOf(ChatChunk.Done.class);
        assertThat(((ChatChunk.Done) chunks.get(3)).usage().totalTokens()).isEqualTo(8);
    }

    @Test
    void functionCallLifecycleYieldsStartDeltaComplete() {
        List<ChatChunk> chunks = new ArrayList<>();
        var d = new OpenAiResponsesStreamDispatcher(JsonCodec.shared(), chunks::add);
        d.onSseEvent("response.output_item.added",
                "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"add\"}}");
        d.onSseEvent("response.function_call_arguments.delta",
                "{\"output_index\":0,\"delta\":\"{\\\"a\\\":\"}");
        d.onSseEvent("response.function_call_arguments.delta",
                "{\"output_index\":0,\"delta\":\"2,\\\"b\\\":3}\"}");
        d.onSseEvent("response.function_call_arguments.done",
                "{\"output_index\":0}");

        assertThat(chunks).hasSize(4);  // start + 2 deltas + complete
        assertThat(chunks.get(0)).isInstanceOf(ChatChunk.ToolCallStart.class);
        var start = (ChatChunk.ToolCallStart) chunks.get(0);
        assertThat(start.id()).isEqualTo("call_1");
        assertThat(start.name()).isEqualTo("add");
        assertThat(chunks.get(3)).isInstanceOf(ChatChunk.ToolCallComplete.class);
        var complete = (ChatChunk.ToolCallComplete) chunks.get(3);
        assertThat(complete.argsJson()).isEqualTo("{\"a\":2,\"b\":3}");
    }

    @Test
    void errorEventEmitsErrorChunk() {
        List<ChatChunk> chunks = new ArrayList<>();
        var d = new OpenAiResponsesStreamDispatcher(JsonCodec.shared(), chunks::add);
        d.onSseEvent("error", "{\"message\":\"rate limited\"}");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isInstanceOf(ChatChunk.Error.class);
        assertThat(((ChatChunk.Error) chunks.get(0)).message()).isEqualTo("rate limited");
    }

    @Test
    void unknownEventTypesAreIgnored() {
        List<ChatChunk> chunks = new ArrayList<>();
        var d = new OpenAiResponsesStreamDispatcher(JsonCodec.shared(), chunks::add);
        d.onSseEvent("response.reasoning_summary_part.added", "{\"item_id\":\"r1\"}");
        d.onSseEvent("response.web_search_call.in_progress", "{}");
        assertThat(chunks).isEmpty();
    }
}
