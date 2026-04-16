package io.kite.schema;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kite.internal.json.JsonCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal JSON-Schema representation. Used for both tool-parameter schemas and structured-output
 * schemas. Emits a JSON-Schema-compatible JSON string via {@link #writeJson()}.
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

    String writeJson();

    default ObjectNode toJackson() {
        return JsonCodec.shared().readTree(writeJson()).deepCopy();
    }

    record Obj(Map<String, SchemaNode> properties, List<String> required, String description, boolean strict)
            implements SchemaNode {
        public Obj {
            properties = properties == null ? Map.of() : new LinkedHashMap<>(properties);
            required = required == null ? List.of() : List.copyOf(required);
        }

        @Override
        public String writeJson() {
            ObjectNode n = JsonCodec.shared().mapper().createObjectNode();
            n.put("type", "object");
            if (description != null) n.put("description", description);
            ObjectNode props = n.putObject("properties");
            for (var e : properties.entrySet()) props.set(e.getKey(), e.getValue().toJackson());
            ArrayNode req = n.putArray("required");
            required.forEach(req::add);
            if (strict) n.put("additionalProperties", false);
            return n.toString();
        }
    }

    record Arr(SchemaNode items, String description) implements SchemaNode {
        @Override
        public String writeJson() {
            ObjectNode n = JsonCodec.shared().mapper().createObjectNode();
            n.put("type", "array");
            if (description != null) n.put("description", description);
            n.set("items", items.toJackson());
            return n.toString();
        }
    }

    record Str(String description, String format) implements SchemaNode {
        public static Str of(String description) { return new Str(description, null); }

        @Override
        public String writeJson() {
            ObjectNode n = JsonCodec.shared().mapper().createObjectNode();
            n.put("type", "string");
            if (description != null) n.put("description", description);
            if (format != null) n.put("format", format);
            return n.toString();
        }
    }

    record Num(String description) implements SchemaNode {
        @Override
        public String writeJson() {
            ObjectNode n = JsonCodec.shared().mapper().createObjectNode();
            n.put("type", "number");
            if (description != null) n.put("description", description);
            return n.toString();
        }
    }

    record Int(String description) implements SchemaNode {
        @Override
        public String writeJson() {
            ObjectNode n = JsonCodec.shared().mapper().createObjectNode();
            n.put("type", "integer");
            if (description != null) n.put("description", description);
            return n.toString();
        }
    }

    record Bool(String description) implements SchemaNode {
        @Override
        public String writeJson() {
            ObjectNode n = JsonCodec.shared().mapper().createObjectNode();
            n.put("type", "boolean");
            if (description != null) n.put("description", description);
            return n.toString();
        }
    }

    record Enumr(List<String> values, String description) implements SchemaNode {
        public Enumr { values = List.copyOf(values); }

        @Override
        public String writeJson() {
            ObjectNode n = JsonCodec.shared().mapper().createObjectNode();
            n.put("type", "string");
            if (description != null) n.put("description", description);
            ArrayNode arr = n.putArray("enum");
            values.forEach(arr::add);
            return n.toString();
        }
    }

    /** Placeholder for types we decide to opaquely allow. */
    record Ref(String description) implements SchemaNode {
        @Override
        public String writeJson() {
            ObjectNode n = JsonCodec.shared().mapper().createObjectNode();
            if (description != null) n.put("description", description);
            return n.toString();
        }
    }
}
