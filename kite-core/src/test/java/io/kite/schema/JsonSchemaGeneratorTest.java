package io.kite.schema;

import io.kite.annotations.Description;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaGeneratorTest {

    record Simple(String name, int age) {}

    record WithDescription(
            @Description("City and country e.g. London, UK") String location,
            @Description("Temperature in celsius") double temperature) {}

    record Nested(String title, Simple inner) {}

    record ListOfStrings(List<String> tags) {}

    record OptionalField(String required, Optional<String> optional) {}

    enum Tier { FREE, PRO, ENTERPRISE }

    record WithEnum(String name, Tier tier) {}

    @Test
    void simpleRecord() {
        String json = JsonSchemaGenerator.forRecord(Simple.class).writeJson();
        assertThat(json).contains("\"type\":\"object\"");
        assertThat(json).contains("\"name\"");
        assertThat(json).contains("\"age\"");
        assertThat(json).contains("\"required\":[\"name\",\"age\"]");
        assertThat(json).contains("\"additionalProperties\":false");
        MetaSchemaValidator.assertValid(json);
    }

    @Test
    void descriptionsAppearInSchema() {
        String json = JsonSchemaGenerator.forRecord(WithDescription.class).writeJson();
        assertThat(json).contains("City and country");
        assertThat(json).contains("Temperature in celsius");
        MetaSchemaValidator.assertValid(json);
    }

    @Test
    void nestedRecordRecurses() {
        String json = JsonSchemaGenerator.forRecord(Nested.class).writeJson();
        assertThat(json).contains("\"inner\"");
        assertThat(json).contains("\"age\"");
        MetaSchemaValidator.assertValid(json);
    }

    @Test
    void listFieldBecomesArray() {
        String json = JsonSchemaGenerator.forRecord(ListOfStrings.class).writeJson();
        assertThat(json).contains("\"type\":\"array\"");
        assertThat(json).contains("\"items\"");
        MetaSchemaValidator.assertValid(json);
    }

    @Test
    void optionalFieldRemovedFromRequired() {
        String json = JsonSchemaGenerator.forRecord(OptionalField.class).writeJson();
        assertThat(json).contains("\"required\":[\"required\"]");
        assertThat(json).contains("\"optional\"");
        MetaSchemaValidator.assertValid(json);
    }

    @Test
    void enumBecomesStringWithValues() {
        String json = JsonSchemaGenerator.forRecord(WithEnum.class).writeJson();
        assertThat(json).contains("\"enum\":[\"FREE\",\"PRO\",\"ENTERPRISE\"]");
        MetaSchemaValidator.assertValid(json);
    }

    @Test
    void schemaCacheReturnsSameInstance() {
        SchemaNode first = JsonSchemaGenerator.forRecord(Simple.class);
        SchemaNode second = JsonSchemaGenerator.forRecord(Simple.class);
        assertThat(first).isSameAs(second);
    }
}
