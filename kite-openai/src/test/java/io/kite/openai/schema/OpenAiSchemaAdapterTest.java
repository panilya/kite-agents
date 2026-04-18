package io.kite.openai.schema;

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

class OpenAiSchemaAdapterTest {

    record Simple(String required, Optional<String> optional) {}

    record Deep(String outer, Inner inner) {}
    record Inner(String name, Optional<Integer> age) {}

    @Test
    void optionalLeafBecomesNullableAndRequired() {
        SchemaNode base = JsonSchemaGenerator.forRecord(Simple.class);
        SchemaNode strict = OpenAiSchemaAdapter.toStrict(base);
        String json = strict.writeJson();

        JsonNode root = JsonCodec.shared().readTree(json);
        JsonNode required = root.get("required");
        assertThat(required.toString()).contains("required").contains("optional");

        JsonNode optProp = root.get("properties").get("optional");
        JsonNode type = optProp.get("type");
        assertThat(type.isArray()).isTrue();
        assertThat(type.toString()).isEqualTo("[\"string\",\"null\"]");

        assertValidMetaSchema(json);
    }

    @Test
    void nestedObjectAlsoRewritten() {
        SchemaNode base = JsonSchemaGenerator.forRecord(Deep.class);
        SchemaNode strict = OpenAiSchemaAdapter.toStrict(base);
        String json = strict.writeJson();

        JsonNode innerAge = JsonCodec.shared().readTree(json)
                .get("properties").get("inner").get("properties").get("age");
        assertThat(innerAge.get("type").toString()).isEqualTo("[\"integer\",\"null\"]");

        JsonNode innerRequired = JsonCodec.shared().readTree(json)
                .get("properties").get("inner").get("required");
        assertThat(innerRequired.toString()).contains("name").contains("age");

        assertValidMetaSchema(json);
    }

    @Test
    void nonNullableFieldsUnchanged() {
        record AllReq(String a, int b) {}
        SchemaNode strict = OpenAiSchemaAdapter.toStrict(JsonSchemaGenerator.forRecord(AllReq.class));
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
