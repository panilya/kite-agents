package io.kite.schema;

import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import io.kite.internal.json.JsonCodec;
import org.assertj.core.api.Assertions;

/**
 * Asserts that a given JSON string is a valid JSON Schema under draft 2020-12. Test helper only.
 */
final class MetaSchemaValidator {

    private static final com.networknt.schema.JsonSchema META =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(SchemaLocation.of(SpecVersion.VersionFlag.V202012.getId()));

    private MetaSchemaValidator() {}

    static void assertValid(String schemaJson) {
        var errors = META.validate(JsonCodec.shared().readTree(schemaJson));
        Assertions.assertThat(errors)
                .as("schema must be valid draft-2020-12; errors: %s\nschema: %s", errors, schemaJson)
                .isEmpty();
    }
}
