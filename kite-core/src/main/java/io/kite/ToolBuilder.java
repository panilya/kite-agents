package io.kite;

import com.fasterxml.jackson.databind.JsonNode;
import io.kite.internal.json.JsonCodec;
import io.kite.schema.JsonSchemaGenerator;
import io.kite.schema.SchemaNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Builder for programmatically-constructed tools. Used for runtime-discovered tools (MCP, plugin
 * systems) and for cases where an annotated method isn't practical.
 */
public final class ToolBuilder {

    private final String name;
    private String description = "";
    private final Map<String, ParamDef> params = new LinkedHashMap<>();
    private Function<Map<String, Object>, Object> noCtxFn;
    private BiFunction<Object, Map<String, Object>, Object> ctxFn;
    private boolean readOnly;

    ToolBuilder(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public ToolBuilder description(String description) {
        this.description = description == null ? "" : description;
        return this;
    }

    public ToolBuilder param(String name, Class<?> type, String description) {
        return param(name, type, description, true, null);
    }

    public ToolBuilder optionalParam(String name, Class<?> type, String description) {
        return param(name, type, description, false, null);
    }

    public ToolBuilder param(String name, Class<?> type, String description, boolean required, Object defaultValue) {
        params.put(name, new ParamDef(name, type, description, required, defaultValue));
        return this;
    }

    /** Execute with no context — args is a name→value map. */
    public <R> ToolBuilder execute(Function<Map<String, Object>, R> fn) {
        this.noCtxFn = fn::apply;
        this.ctxFn = null;
        return this;
    }

    /** Execute with context — context is the first parameter. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T, R> ToolBuilder executeWithContext(BiFunction<T, Map<String, Object>, R> fn) {
        this.ctxFn = (BiFunction) fn;
        this.noCtxFn = null;
        return this;
    }

    /**
     * Declare this tool as read-only: it has no externally-observable side effects (e.g. a
     * database read, a vector-store lookup, a web fetch you're willing to re-issue). Kite can
     * then start it in parallel with any still-running parallel input guards — overlapping the
     * tool's latency with the guard wait — instead of waiting for guards to pass first. If a
     * guard blocks, the tool's result is thrown away and nothing leaks to the caller; only
     * the CPU/IO the tool already consumed is wasted. Defaults to false.
     */
    public ToolBuilder readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public Tool build() {
        if (noCtxFn == null && ctxFn == null) {
            throw new IllegalStateException("Tool '" + name + "' has no execute(...) function configured");
        }
        SchemaNode schema = buildSchema();
        boolean usesContext = ctxFn != null;
        var invoker = new ToolInvokerImpl(name, params, noCtxFn, ctxFn);
        return new Tool(name, description, schema, invoker, usesContext, Tool.Kind.FUNCTION, null, null, readOnly);
    }

    private SchemaNode buildSchema() {
        Map<String, SchemaNode> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (var p : params.values()) {
            SchemaNode node = JsonSchemaGenerator.forType(p.type);
            if (p.description != null) node = withDescription(node, p.description);
            props.put(p.name, node);
            if (p.required) required.add(p.name);
        }
        return new SchemaNode.Obj(props, required, null, true);
    }

    private static SchemaNode withDescription(SchemaNode node, String description) {
        return switch (node) {
            case SchemaNode.Str s -> new SchemaNode.Str(description, s.format());
            case SchemaNode.Num n -> new SchemaNode.Num(description);
            case SchemaNode.Int i -> new SchemaNode.Int(description);
            case SchemaNode.Bool b -> new SchemaNode.Bool(description);
            case SchemaNode.Enumr e -> new SchemaNode.Enumr(e.values(), description);
            case SchemaNode.Arr a -> new SchemaNode.Arr(a.items(), description);
            case SchemaNode.Obj o -> new SchemaNode.Obj(o.properties(), o.required(), description, o.strict());
            case SchemaNode.Ref r -> new SchemaNode.Ref(description);
        };
    }

    record ParamDef(String name, Class<?> type, String description, boolean required, Object defaultValue) {}

    private static final class ToolInvokerImpl implements io.kite.internal.runtime.ToolInvoker {
        private final String name;
        private final Map<String, ParamDef> params;
        private final Function<Map<String, Object>, Object> noCtxFn;
        private final BiFunction<Object, Map<String, Object>, Object> ctxFn;

        ToolInvokerImpl(String name,
                        Map<String, ParamDef> params,
                        Function<Map<String, Object>, Object> noCtxFn,
                        BiFunction<Object, Map<String, Object>, Object> ctxFn) {
            this.name = name;
            this.params = params;
            this.noCtxFn = noCtxFn;
            this.ctxFn = ctxFn;
        }

        @Override
        public String invoke(Object context, String argsJson) {
            JsonNode node = JsonCodec.shared().readTreeOrEmpty(argsJson);
            Map<String, Object> args = new LinkedHashMap<>();
            for (var p : params.values()) {
                JsonNode field = node.get(p.name);
                if (field == null || field.isNull()) {
                    if (p.required && p.defaultValue == null) {
                        throw new IllegalArgumentException("Missing required parameter '" + p.name + "' for tool '" + name + "'");
                    }
                    args.put(p.name, p.defaultValue);
                    continue;
                }
                args.put(p.name, JsonCodec.shared().treeToValue(field, p.type));
            }
            Object result = noCtxFn != null ? noCtxFn.apply(args) : ctxFn.apply(context, args);
            if (result == null) return "null";
            if (result instanceof String s) return JsonCodec.shared().writeValueAsString(s);
            return JsonCodec.shared().writeValueAsString(result);
        }
    }
}
