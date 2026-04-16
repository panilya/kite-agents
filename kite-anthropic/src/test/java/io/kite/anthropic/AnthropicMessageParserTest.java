package io.kite.anthropic;

import io.kite.internal.json.JsonCodec;
import io.kite.model.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicMessageParserTest {

    @Test
    void parsesTextContent() {
        String body = """
                {
                  "id": "msg_1",
                  "model": "claude-3-5-sonnet-20241022",
                  "stop_reason": "end_turn",
                  "content": [
                    {"type": "text", "text": "Hello!"}
                  ],
                  "usage": {"input_tokens": 10, "output_tokens": 4}
                }
                """;
        ChatResponse resp = AnthropicMessageParser.parse(JsonCodec.shared().readTree(body));
        assertThat(resp.content()).isEqualTo("Hello!");
        assertThat(resp.toolCalls()).isEmpty();
        assertThat(resp.usage().promptTokens()).isEqualTo(10);
        assertThat(resp.usage().completionTokens()).isEqualTo(4);
    }

    @Test
    void parsesToolUse() {
        String body = """
                {
                  "id": "msg_2",
                  "stop_reason": "tool_use",
                  "content": [
                    {"type": "text", "text": "Let me check."},
                    {"type": "tool_use", "id": "toolu_1", "name": "search", "input": {"q": "hello"}}
                  ],
                  "usage": {"input_tokens": 20, "output_tokens": 8}
                }
                """;
        ChatResponse resp = AnthropicMessageParser.parse(JsonCodec.shared().readTree(body));
        assertThat(resp.content()).isEqualTo("Let me check.");
        assertThat(resp.toolCalls()).hasSize(1);
        var call = resp.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("toolu_1");
        assertThat(call.name()).isEqualTo("search");
        assertThat(call.argsJson()).contains("\"q\":\"hello\"");
    }
}
