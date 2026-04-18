package io.kite.anthropic.schema;

import io.kite.schema.SchemaNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transforms a provider-agnostic {@link SchemaNode} tree into the shape Anthropic accepts in
 * {@code output_config.format = json_schema} (their strict structured-output path).
 *
 * <p>Same rewrite as OpenAI strict: optionals become {@code type: ["T", "null"]} unions and join
 * {@code required}. Unlike OpenAI, Anthropic tool {@code input_schema} uses absent-from-required
 * for optionals and does not go through this adapter.
 */
public final class AnthropicSchemaAdapter {

    private AnthropicSchemaAdapter() {}

    public static SchemaNode toStrictOutput(SchemaNode node) {
        return switch (node) {
            case SchemaNode.Obj o -> rewriteObj(o);
            case SchemaNode.Arr a -> new SchemaNode.Arr(toStrictOutput(a.items()), a.description());
            default -> node;
        };
    }

    private static SchemaNode rewriteObj(SchemaNode.Obj o) {
        Map<String, SchemaNode> newProps = new LinkedHashMap<>();
        List<String> newRequired = new ArrayList<>(o.required());
        for (var e : o.properties().entrySet()) {
            String name = e.getKey();
            SchemaNode child = toStrictOutput(e.getValue());
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
            default -> node;
        };
    }
}
