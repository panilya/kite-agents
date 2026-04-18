package io.kite.openai;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiResponsesSerializerTest {

    record Booking(String airline, String flight) {}
    record BookingWithOptional(String airline, Optional<String> seat) {}

    @Test
    void serializesSimpleUserMessage() {
        var req = new ChatRequest(
                "gpt-4o",
                "You are helpful.",
                List.of(new Message.User("Hello")),
                List.of(),
                null, null,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(OpenAiResponsesSerializer.serialize(req, false));
        assertThat(root.get("model").asText()).isEqualTo("gpt-4o");
        assertThat(root.get("instructions").asText()).isEqualTo("You are helpful.");
        JsonNode input = root.get("input");
        assertThat(input).isNotNull();
        assertThat(input.get(0).get("role").asText()).isEqualTo("user");
        assertThat(input.get(0).get("content").asText()).isEqualTo("Hello");
        assertThat(root.get("stream").asBoolean()).isFalse();
        assertThat(root.get("store").asBoolean()).isFalse();
    }

    @Test
    void serializesToolsInFlatResponsesFormat() {
        var schema = JsonSchemaGenerator.forRecord(Booking.class);
        var req = new ChatRequest(
                "gpt-4o", null,
                List.of(new Message.User("Book me")),
                List.of(new ChatRequest.ToolSchema("book", "Book a flight", schema)),
                null, null,
                null, null, null, null, true);
        JsonNode root = JsonCodec.shared().readTree(OpenAiResponsesSerializer.serialize(req, true));
        JsonNode tools = root.get("tools");
        assertThat(tools).isNotNull();
        assertThat(tools.isArray()).isTrue();
        JsonNode tool = tools.get(0);
        // Flat shape: type/name/description/parameters/strict all at top level, no nested "function".
        assertThat(tool.get("type").asText()).isEqualTo("function");
        assertThat(tool.get("name").asText()).isEqualTo("book");
        assertThat(tool.get("strict").asBoolean()).isTrue();
        assertThat(tool.has("function")).isFalse();
        assertThat(tool.get("parameters")).isNotNull();
        assertThat(root.get("stream").asBoolean()).isTrue();
    }

    @Test
    void toolParametersGoThroughStrictAdapter_optionalBecomesNullableInRequired() {
        var schema = JsonSchemaGenerator.forRecord(BookingWithOptional.class);
        var req = new ChatRequest(
                "gpt-4o", null,
                List.of(new Message.User("Book me")),
                List.of(new ChatRequest.ToolSchema("book", "", schema)),
                null, null,
                null, null, null, null, false);
        JsonNode params = JsonCodec.shared().readTree(OpenAiResponsesSerializer.serialize(req, false))
                .get("tools").get(0).get("parameters");
        // Every property in required, optional leaf is a nullable union.
        assertThat(params.get("required").toString()).contains("airline").contains("seat");
        assertThat(params.get("properties").get("seat").get("type").toString())
                .isEqualTo("[\"string\",\"null\"]");
    }

    @Test
    void outputSchemaGoesThroughStrictAdapter() {
        var schema = JsonSchemaGenerator.forRecord(BookingWithOptional.class);
        var req = new ChatRequest(
                "gpt-4o", null,
                List.of(new Message.User("Extract")),
                List.of(),
                null, null,
                schema, "Booking", null, null, false);
        JsonNode format = JsonCodec.shared().readTree(OpenAiResponsesSerializer.serialize(req, false))
                .get("text").get("format");
        assertThat(format.get("strict").asBoolean()).isTrue();
        JsonNode s = format.get("schema");
        assertThat(s.get("required").toString()).contains("airline").contains("seat");
        assertThat(s.get("properties").get("seat").get("type").toString())
                .isEqualTo("[\"string\",\"null\"]");
    }

    @Test
    void serializesPriorToolCallsAsFunctionCallItems() {
        var req = new ChatRequest(
                "gpt-4o", null,
                List.of(
                        new Message.User("Refund it"),
                        new Message.Assistant("", List.of(new Message.ToolCallRef("call_1", "refund", "{\"id\":99}"))),
                        new Message.Tool("call_1", "refund", "\"ok\"")
                ),
                List.of(),
                null, null,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(OpenAiResponsesSerializer.serialize(req, false));
        JsonNode input = root.get("input");
        // user, function_call, function_call_output (assistant content was empty → no assistant entry)
        assertThat(input).hasSize(3);
        assertThat(input.get(0).get("role").asText()).isEqualTo("user");
        assertThat(input.get(1).get("type").asText()).isEqualTo("function_call");
        assertThat(input.get(1).get("call_id").asText()).isEqualTo("call_1");
        assertThat(input.get(1).get("name").asText()).isEqualTo("refund");
        assertThat(input.get(2).get("type").asText()).isEqualTo("function_call_output");
        assertThat(input.get(2).get("call_id").asText()).isEqualTo("call_1");
        assertThat(input.get(2).get("output").asText()).isEqualTo("\"ok\"");
    }

    @Test
    void throwsOnUnpairedFunctionCall() {
        // Guard against future Runner regressions: an Assistant with a ToolCallRef that has
        // no matching Message.Tool in history must fail fast at the serializer, before the
        // HTTP round-trip, with a message that points at the offending call.
        var req = new ChatRequest(
                "gpt-4o", null,
                List.of(
                        new Message.User("Do it"),
                        new Message.Assistant("", List.of(new Message.ToolCallRef("call_x", "refund", "{}"))),
                        new Message.User("follow up")
                ),
                List.of(),
                null, null,
                null, null, null, null, false);
        assertThatThrownBy(() -> OpenAiResponsesSerializer.serialize(req, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("call_x")
                .hasMessageContaining("refund");
    }

    @Test
    void toolChoiceAbsentWhenNull() {
        var req = new ChatRequest(
                "gpt-4o", null,
                List.of(new Message.User("Hi")),
                List.of(),
                null, null,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(OpenAiResponsesSerializer.serialize(req, false));
        assertThat(root.has("tool_choice")).isFalse();
        assertThat(root.has("parallel_tool_calls")).isFalse();
    }

    @Test
    void toolChoiceAutoNoneRequiredEmittedAsStrings() {
        for (var pair : List.of(
                new Object[]{ToolChoice.auto(), "auto"},
                new Object[]{ToolChoice.none(), "none"},
                new Object[]{ToolChoice.required(), "required"})) {
            ToolChoice tc = (ToolChoice) pair[0];
            String expected = (String) pair[1];
            var req = new ChatRequest(
                    "gpt-4o", null,
                    List.of(new Message.User("Hi")),
                    List.of(),
                    tc, null,
                    null, null, null, null, false);
            JsonNode root = JsonCodec.shared().readTree(OpenAiResponsesSerializer.serialize(req, false));
            assertThat(root.get("tool_choice").isTextual())
                    .as("tool_choice for %s should be a plain string", expected)
                    .isTrue();
            assertThat(root.get("tool_choice").asText()).isEqualTo(expected);
        }
    }

    @Test
    void toolChoiceSpecificEmittedAsFunctionObject() {
        var req = new ChatRequest(
                "gpt-4o", null,
                List.of(new Message.User("Hi")),
                List.of(),
                ToolChoice.tool("get_weather"), null,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(OpenAiResponsesSerializer.serialize(req, false));
        JsonNode tc = root.get("tool_choice");
        assertThat(tc.isObject()).isTrue();
        assertThat(tc.get("type").asText()).isEqualTo("function");
        assertThat(tc.get("name").asText()).isEqualTo("get_weather");
    }

    @Test
    void parallelToolCallsForwardedWhenTrue() {
        var req = new ChatRequest(
                "gpt-4o", null,
                List.of(new Message.User("Hi")),
                List.of(),
                null, Boolean.TRUE,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(OpenAiResponsesSerializer.serialize(req, false));
        assertThat(root.get("parallel_tool_calls").asBoolean()).isTrue();
    }

    @Test
    void parallelToolCallsForwardedWhenFalse() {
        var req = new ChatRequest(
                "gpt-4o", null,
                List.of(new Message.User("Hi")),
                List.of(),
                null, Boolean.FALSE,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(OpenAiResponsesSerializer.serialize(req, false));
        assertThat(root.get("parallel_tool_calls").asBoolean()).isFalse();
    }

    @Test
    void toolChoiceAndParallelToolCallsEmittedIndependently() {
        var req = new ChatRequest(
                "gpt-4o", null,
                List.of(new Message.User("Hi")),
                List.of(),
                ToolChoice.required(), Boolean.FALSE,
                null, null, null, null, false);
        JsonNode root = JsonCodec.shared().readTree(OpenAiResponsesSerializer.serialize(req, false));
        assertThat(root.get("tool_choice").asText()).isEqualTo("required");
        assertThat(root.get("parallel_tool_calls").asBoolean()).isFalse();
    }

    @Test
    void serializesStructuredOutput() {
        var schema = JsonSchemaGenerator.forRecord(Booking.class);
        var req = new ChatRequest(
                "gpt-4o", null,
                List.of(new Message.User("Book United 123")),
                List.of(),
                null, null,
                schema, "Booking", null, null, false);
        JsonNode root = JsonCodec.shared().readTree(OpenAiResponsesSerializer.serialize(req, false));
        JsonNode format = root.get("text").get("format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        assertThat(format.get("name").asText()).isEqualTo("Booking");
        assertThat(format.get("strict").asBoolean()).isTrue();
        assertThat(format.get("schema")).isNotNull();
    }
}
