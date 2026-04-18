package io.kite.anthropic.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import io.kite.internal.json.JsonCodec;
import io.kite.schema.JsonSchemaGenerator;
import io.kite.schema.SchemaNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicSchemaAdapterTest {

    record OutputShape(String summary, Optional<Integer> confidence) {}

    @Test
    void optionalLeafBecomesNullableAndRequired() {
        SchemaNode base = JsonSchemaGenerator.forRecord(OutputShape.class);
        SchemaNode strict = AnthropicSchemaAdapter.toStrictOutput(base);
        String json = strict.writeJson();

        JsonNode root = JsonCodec.shared().readTree(json);
        assertThat(root.get("required").toString()).contains("summary").contains("confidence");

        JsonNode confType = root.get("properties").get("confidence").get("type");
        assertThat(confType.toString()).isEqualTo("[\"integer\",\"null\"]");

        assertValidMetaSchema(json);
    }

    @Test
    void allRequiredUnchanged() {
        record AllReq(String a, int b) {}
        SchemaNode strict = AnthropicSchemaAdapter.toStrictOutput(JsonSchemaGenerator.forRecord(AllReq.class));
        JsonNode root = JsonCodec.shared().readTree(strict.writeJson());
        assertThat(root.get("properties").get("a").get("type").asText()).isEqualTo("string");
        assertThat(root.get("properties").get("b").get("type").asText()).isEqualTo("integer");
    }

    private static void assertValidMetaSchema(String schemaJson) {
        var meta = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(SchemaLocation.of(SpecVersion.VersionFlag.V202012.getId()));
        var errors = meta.validate(JsonCodec.shared().readTree(schemaJson));
        assertThat(errors).as("draft-2020-12 errors: %s", errors).isEmpty();
    }
}
