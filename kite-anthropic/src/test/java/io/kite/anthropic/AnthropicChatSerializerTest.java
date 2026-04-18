package io.kite.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import io.kite.ToolChoice;
import io.kite.internal.json.JsonCodec;
import io.kite.model.ChatRequest;
import io.kite.model.Message;
import io.kite.schema.JsonSchemaGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicChatSerializerTest {

    record Args(String q) {}
    record ArgsWithOptional(String q, Optional<Integer> limit) {}
    record Booking(String airline, int flightNumber) {}
    record BookingWithOptional(String airline, Optional<Integer> flightNumber) {}

    @Test
    void systemPromptBecomesTopLevelField() {
        var req = new ChatRequest(
                "claude-3-5-sonnet-20241022",
                "You are terse.",
                List.of(new Message.User("Hi")),
                List.of(),
                null, null,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false));
        assertThat(root.get("system").asText()).isEqualTo("You are terse.");
        assertThat(root.get("max_tokens").asInt()).isEqualTo(4096);
        assertThat(root.get("messages").get(0).get("role").asText()).isEqualTo("user");
    }

    @Test
    void toolsUseInputSchemaField() {
        var req = new ChatRequest(
                "claude-3-5-sonnet-20241022", null,
                List.of(new Message.User("Search")),
                List.of(new ChatRequest.ToolSchema("search", "Search the web",
                        JsonSchemaGenerator.forRecord(Args.class))),
                null, null,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false));
        JsonNode tool = root.get("tools").get(0);
        assertThat(tool.get("name").asText()).isEqualTo("search");
        assertThat(tool.has("input_schema")).isTrue();
        assertThat(tool.has("parameters")).isFalse();
    }

    @Test
    void toolInputSchemaKeepsOptionalAbsentFromRequired_noAdapterRewrite() {
        var req = new ChatRequest(
                "claude-3-5-sonnet-20241022", null,
                List.of(new Message.User("Search")),
                List.of(new ChatRequest.ToolSchema("search", "",
                        JsonSchemaGenerator.forRecord(ArgsWithOptional.class))),
                null, null,
                null, null, null, null, false);
        JsonNode inputSchema = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false))
                .get("tools").get(0).get("input_schema");
        // Anthropic tool path is lenient: optional stays out of required, and its leaf is NOT nullable.
        assertThat(inputSchema.get("required").toString()).contains("q").doesNotContain("limit");
        assertThat(inputSchema.get("properties").get("limit").get("type").asText()).isEqualTo("integer");
    }

    @Test
    void outputConfigGoesThroughStrictAdapter() {
        var schema = JsonSchemaGenerator.forRecord(BookingWithOptional.class);
        var req = new ChatRequest(
                "claude-3-5-sonnet-20241022", null,
                List.of(new Message.User("Extract")),
                List.of(),
                null, null,
                schema, "Booking", null, null, false);
        JsonNode outputSchema = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false))
                .get("output_config").get("format").get("schema");
        // Strict output: flightNumber becomes nullable-in-required.
        assertThat(outputSchema.get("required").toString()).contains("airline").contains("flightNumber");
        assertThat(outputSchema.get("properties").get("flightNumber").get("type").toString())
                .isEqualTo("[\"integer\",\"null\"]");
    }

    @Test
    void assistantToolCallBecomesContentBlock() {
        var req = new ChatRequest(
                "claude-3-5-sonnet-20241022", null,
                List.of(
                        new Message.User("Do it"),
                        new Message.Assistant("", List.of(new Message.ToolCallRef("tool_1", "refund", "{\"id\":99}"))),
                        new Message.Tool("tool_1", "refund", "\"ok\"")
                ),
                List.of(),
                null, null,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false));
        JsonNode messages = root.get("messages");
        // user, assistant (tool_use block), user (tool_result block)
        assertThat(messages).hasSize(3);
        JsonNode asst = messages.get(1);
        assertThat(asst.get("role").asText()).isEqualTo("assistant");
        JsonNode useBlock = asst.get("content").get(0);
        assertThat(useBlock.get("type").asText()).isEqualTo("tool_use");
        assertThat(useBlock.get("id").asText()).isEqualTo("tool_1");
        assertThat(useBlock.get("input").get("id").asInt()).isEqualTo(99);

        JsonNode toolResultUser = messages.get(2);
        assertThat(toolResultUser.get("role").asText()).isEqualTo("user");
        JsonNode resultBlock = toolResultUser.get("content").get(0);
        assertThat(resultBlock.get("type").asText()).isEqualTo("tool_result");
        assertThat(resultBlock.get("tool_use_id").asText()).isEqualTo("tool_1");
    }

    @Test
    void toolChoiceAbsentWhenBothNull() {
        var req = new ChatRequest(
                "claude-3-5-sonnet-20241022", null,
                List.of(new Message.User("Hi")),
                List.of(),
                null, null,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false));
        assertThat(root.has("tool_choice")).isFalse();
    }

    @Test
    void toolChoiceAutoNoneAnyEmittedAsTypeObject() {
        for (var pair : List.of(
                new Object[]{ToolChoice.auto(), "auto"},
                new Object[]{ToolChoice.none(), "none"},
                new Object[]{ToolChoice.required(), "any"})) {
            ToolChoice tc = (ToolChoice) pair[0];
            String expectedType = (String) pair[1];
            var req = new ChatRequest(
                    "claude-3-5-sonnet-20241022", null,
                    List.of(new Message.User("Hi")),
                    List.of(),
                    tc, null,
                    null, null, null, null, false);
            JsonNode root = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false));
            JsonNode choice = root.get("tool_choice");
            assertThat(choice).as("tool_choice for %s", expectedType).isNotNull();
            assertThat(choice.isObject()).isTrue();
            assertThat(choice.get("type").asText()).isEqualTo(expectedType);
            assertThat(choice.has("disable_parallel_tool_use")).isFalse();
        }
    }

    @Test
    void toolChoiceSpecificBecomesToolTypeWithName() {
        var req = new ChatRequest(
                "claude-3-5-sonnet-20241022", null,
                List.of(new Message.User("Hi")),
                List.of(),
                ToolChoice.tool("search"), null,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false));
        JsonNode choice = root.get("tool_choice");
        assertThat(choice.get("type").asText()).isEqualTo("tool");
        assertThat(choice.get("name").asText()).isEqualTo("search");
    }

    @Test
    void parallelToolCallsFalseAloneAutoWrapsWithDisableFlag() {
        // THE load-bearing fusion test. User only set parallelToolCalls(false);
        // serializer must auto-wrap with {type:"auto", disable_parallel_tool_use:true}.
        var req = new ChatRequest(
                "claude-3-5-sonnet-20241022", null,
                List.of(new Message.User("Hi")),
                List.of(),
                null, Boolean.FALSE,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false));
        JsonNode choice = root.get("tool_choice");
        assertThat(choice).isNotNull();
        assertThat(choice.get("type").asText()).isEqualTo("auto");
        assertThat(choice.get("disable_parallel_tool_use").asBoolean()).isTrue();
    }

    @Test
    void parallelToolCallsTrueDoesNotEmitDisableFlag() {
        var req = new ChatRequest(
                "claude-3-5-sonnet-20241022", null,
                List.of(new Message.User("Hi")),
                List.of(),
                null, Boolean.TRUE,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false));
        // true is the default — no wrapping required. Keep the payload minimal.
        assertThat(root.has("tool_choice")).isFalse();
    }

    @Test
    void specificWithParallelFalseFusesIntoSingleObject() {
        var req = new ChatRequest(
                "claude-3-5-sonnet-20241022", null,
                List.of(new Message.User("Hi")),
                List.of(),
                ToolChoice.tool("foo"), Boolean.FALSE,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false));
        JsonNode choice = root.get("tool_choice");
        assertThat(choice.get("type").asText()).isEqualTo("tool");
        assertThat(choice.get("name").asText()).isEqualTo("foo");
        assertThat(choice.get("disable_parallel_tool_use").asBoolean()).isTrue();
    }

    @Test
    void requiredWithParallelTrueEmitsAnyWithoutDisableFlag() {
        var req = new ChatRequest(
                "claude-3-5-sonnet-20241022", null,
                List.of(new Message.User("Hi")),
                List.of(),
                ToolChoice.required(), Boolean.TRUE,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false));
        JsonNode choice = root.get("tool_choice");
        assertThat(choice.get("type").asText()).isEqualTo("any");
        assertThat(choice.has("disable_parallel_tool_use")).isFalse();
    }

    @Test
    void serializesStructuredOutput() {
        var schema = JsonSchemaGenerator.forRecord(Booking.class);
        var req = new ChatRequest(
                "claude-opus-4-7", null,
                List.of(new Message.User("Book United 123")),
                List.of(),
                null, null,
                schema, "Booking", null, null, false);
        JsonNode root = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false));
        JsonNode format = root.get("output_config").get("format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        JsonNode schemaNode = format.get("schema");
        assertThat(schemaNode).isNotNull();
        assertThat(schemaNode.get("type").asText()).isEqualTo("object");
        assertThat(schemaNode.get("additionalProperties").asBoolean()).isFalse();
        assertThat(schemaNode.get("properties").has("airline")).isTrue();
        assertThat(schemaNode.get("properties").has("flightNumber")).isTrue();
    }

    @Test
    void outputConfigAbsentWhenSchemaNull() {
        var req = new ChatRequest(
                "claude-opus-4-7", null,
                List.of(new Message.User("Hi")),
                List.of(),
                null, null,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(AnthropicChatSerializer.serialize(req, false));
        assertThat(root.has("output_config")).isFalse();
    }
}
