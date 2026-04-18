package io.kite.openai.schema;

import io.kite.schema.SchemaNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transforms a provider-agnostic {@link SchemaNode} tree into the shape OpenAI accepts under
 * {@code strict: true} (both function-tool {@code parameters} and {@code text.format = json_schema}).
 *
 * <p>Strict mode requires every property to appear in {@code required}; optionals must be expressed
 * as {@code type: ["T", "null"]} unions. This walker rewrites any {@code Obj} where properties are
 * absent from {@code required} by marking the corresponding leaf nullable and appending the name to
 * {@code required}, then recurses into nested objects and array items.
 */
public final class OpenAiSchemaAdapter {

    private OpenAiSchemaAdapter() {}

    public static SchemaNode toStrict(SchemaNode node) {
        return switch (node) {
            case SchemaNode.Obj o -> rewriteObj(o);
            case SchemaNode.Arr a -> new SchemaNode.Arr(toStrict(a.items()), a.description());
            default -> node;
        };
    }

    private static SchemaNode rewriteObj(SchemaNode.Obj o) {
        Map<String, SchemaNode> newProps = new LinkedHashMap<>();
        List<String> newRequired = new ArrayList<>(o.required());
        for (var e : o.properties().entrySet()) {
            String name = e.getKey();
            SchemaNode child = toStrict(e.getValue());
            if (!o.required().contains(name)) {
                child = asNullable(child);
                newRequired.add(name);
            }
            newProps.put(name, child);
        }
        return new SchemaNode.Obj(newProps, newRequired, o.description(), o.strict());
    }

    private static SchemaNode asNullable(SchemaNode node) {
        return switch (node) {
            case SchemaNode.Str s   -> s.asNullable();
            case SchemaNode.Num n   -> n.asNullable();
            case SchemaNode.Int i   -> i.asNullable();
            case SchemaNode.Bool b  -> b.asNullable();
            case SchemaNode.Enumr e -> e.asNullable();
            // Obj/Arr/Ref: strict mode treats unions of objects/arrays specially; leave alone
            // until there's a concrete need. Callers won't hit this in v1 because Optional<Record>
            // isn't a supported shape.
            default -> node;
        };
    }
}
