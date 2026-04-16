package io.kite.openai;

import io.kite.internal.json.JsonCodec;
import io.kite.model.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiResponsesParserTest {

    @Test
    void parsesSimpleTextResponse() {
        String body = """
                {
                  "id": "resp_abc",
                  "model": "gpt-4o",
                  "status": "completed",
                  "output": [
                    {"type": "message", "role": "assistant", "content": [
                      {"type": "output_text", "text": "Hello, world!"}
                    ]}
                  ],
                  "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}
                }
                """;
        ChatResponse resp = OpenAiResponsesParser.parse(JsonCodec.shared().readTree(body));
        assertThat(resp.content()).isEqualTo("Hello, world!");
        assertThat(resp.toolCalls()).isEmpty();
        assertThat(resp.usage().totalTokens()).isEqualTo(15);
        assertThat(resp.model()).isEqualTo("gpt-4o");
        assertThat(resp.id()).isEqualTo("resp_abc");
    }

    @Test
    void parsesFunctionCall() {
        String body = """
                {
                  "id": "resp_xyz",
                  "model": "gpt-4o",
                  "output": [
                    {"type": "function_call", "call_id": "call_99", "name": "add",
                     "arguments": "{\\"a\\":2,\\"b\\":3}"}
                  ],
                  "usage": {"input_tokens": 20, "output_tokens": 8, "total_tokens": 28}
                }
                """;
        ChatResponse resp = OpenAiResponsesParser.parse(JsonCodec.shared().readTree(body));
        assertThat(resp.content()).isEmpty();
        assertThat(resp.toolCalls()).hasSize(1);
        var call = resp.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_99");
        assertThat(call.name()).isEqualTo("add");
        assertThat(call.argsJson()).isEqualTo("{\"a\":2,\"b\":3}");
    }

    @Test
    void parsesMixedTextAndToolCall() {
        String body = """
                {
                  "output": [
                    {"type": "message", "role": "assistant", "content": [
                      {"type": "output_text", "text": "Let me check."}
                    ]},
                    {"type": "function_call", "call_id": "c1", "name": "lookup",
                     "arguments": "{\\"id\\":42}"}
                  ]
                }
                """;
        ChatResponse resp = OpenAiResponsesParser.parse(JsonCodec.shared().readTree(body));
        assertThat(resp.content()).isEqualTo("Let me check.");
        assertThat(resp.toolCalls()).hasSize(1);
    }

    @Test
    void ignoresReasoningItems() {
        String body = """
                {
                  "output": [
                    {"type": "reasoning", "id": "r1"},
                    {"type": "message", "role": "assistant", "content": [
                      {"type": "output_text", "text": "Done."}
                    ]}
                  ]
                }
                """;
        ChatResponse resp = OpenAiResponsesParser.parse(JsonCodec.shared().readTree(body));
        assertThat(resp.content()).isEqualTo("Done.");
    }
}
