package io.kite.schema;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kite.internal.json.JsonCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal JSON-Schema representation. Used for both tool-parameter schemas and structured-output
 * schemas. {@link #toJackson()} builds a fresh {@link ObjectNode} directly; {@link #writeJson()}
 * is its string form.
 *
 * <p>Leaf variants carry a {@code nullable} flag used by provider adapters to emit
 * {@code type: ["string", "null"]} unions when a provider's strict mode requires every property
 * to be {@code required} (OpenAI, Anthropic output_config). The base IR stays provider-agnostic;
 * see {@code OpenAiSchemaAdapter} / {@code AnthropicSchemaAdapter} for the rewrite.
 */
public sealed interface SchemaNode
        permits SchemaNode.Obj,
                SchemaNode.Arr,
                SchemaNode.Str,
                SchemaNode.Num,
                SchemaNode.Int,
                SchemaNode.Bool,
                SchemaNode.Enumr,
                SchemaNode.Ref {

    ObjectNode toJackson();

    SchemaNode withDescription(String description);

    default String writeJson() {
        return toJackson().toString();
    }

    private static ObjectNode newNode() {
        return JsonCodec.shared().mapper().createObjectNode();
    }

    private static void putType(ObjectNode n, String type, boolean nullable) {
        if (nullable) {
            ArrayNode arr = n.putArray("type");
            arr.add(type);
            arr.add("null");
        } else {
            n.put("type", type);
        }
    }

    record Obj(Map<String, SchemaNode> properties, List<String> required, String description, boolean strict)
            implements SchemaNode {
        public Obj {
            properties = properties == null ? Map.of() : new LinkedHashMap<>(properties);
            required = required == null ? List.of() : List.copyOf(required);
        }

        @Override
        public ObjectNode toJackson() {
            ObjectNode n = newNode();
            n.put("type", "object");
            if (description != null) n.put("description", description);
            ObjectNode props = n.putObject("properties");
            for (var e : properties.entrySet()) props.set(e.getKey(), e.getValue().toJackson());
            ArrayNode req = n.putArray("required");
            required.forEach(req::add);
            if (strict) n.put("additionalProperties", false);
            return n;
        }

        @Override
        public Obj withDescription(String description) {
            return new Obj(properties, required, description, strict);
        }
    }

    record Arr(SchemaNode items, String description) implements SchemaNode {
        @Override
        public ObjectNode toJackson() {
            ObjectNode n = newNode();
            n.put("type", "array");
            if (description != null) n.put("description", description);
            n.set("items", items.toJackson());
            return n;
        }

        @Override
        public Arr withDescription(String description) {
            return new Arr(items, description);
        }
    }

    record Str(String description, String format, boolean nullable) implements SchemaNode {
        public static Str of(String description) { return new Str(description, null, false); }

        @Override
        public ObjectNode toJackson() {
            ObjectNode n = newNode();
            putType(n, "string", nullable);
            if (description != null) n.put("description", description);
            if (format != null) n.put("format", format);
            return n;
        }

        @Override
        public Str withDescription(String description) {
            return new Str(description, format, nullable);
        }

        public Str asNullable() {
            return nullable ? this : new Str(description, format, true);
        }
    }

    record Num(String description, boolean nullable) implements SchemaNode {
        @Override
        public ObjectNode toJackson() {
            ObjectNode n = newNode();
            putType(n, "number", nullable);
            if (description != null) n.put("description", description);
            return n;
        }

        @Override
        public Num withDescription(String description) {
            return new Num(description, nullable);
        }

        public Num asNullable() {
            return nullable ? this : new Num(description, true);
        }
    }

    record Int(String description, boolean nullable) implements SchemaNode {
        @Override
        public ObjectNode toJackson() {
            ObjectNode n = newNode();
            putType(n, "integer", nullable);
            if (description != null) n.put("description", description);
            return n;
        }

        @Override
        public Int withDescription(String description) {
            return new Int(description, nullable);
        }

        public Int asNullable() {
            return nullable ? this : new Int(description, true);
        }
    }

    record Bool(String description, boolean nullable) implements SchemaNode {
        @Override
        public ObjectNode toJackson() {
            ObjectNode n = newNode();
            putType(n, "boolean", nullable);
            if (description != null) n.put("description", description);
            return n;
        }

        @Override
        public Bool withDescription(String description) {
            return new Bool(description, nullable);
        }

        public Bool asNullable() {
            return nullable ? this : new Bool(description, true);
        }
    }

    record Enumr(List<String> values, String description, boolean nullable) implements SchemaNode {
        public Enumr { values = List.copyOf(values); }

        @Override
        public ObjectNode toJackson() {
            ObjectNode n = newNode();
            putType(n, "string", nullable);
            if (description != null) n.put("description", description);
            ArrayNode arr = n.putArray("enum");
            values.forEach(arr::add);
            if (nullable) arr.addNull();
            return n;
        }

        @Override
        public Enumr withDescription(String description) {
            return new Enumr(values, description, nullable);
        }

        public Enumr asNullable() {
            return nullable ? this : new Enumr(values, description, true);
        }
    }

    /** Placeholder for types we decide to opaquely allow. */
    record Ref(String description) implements SchemaNode {
        @Override
        public ObjectNode toJackson() {
            ObjectNode n = newNode();
            if (description != null) n.put("description", description);
            return n;
        }

        @Override
        public Ref withDescription(String description) {
            return new Ref(description);
        }
    }
}
