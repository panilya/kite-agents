package io.kite.schema;

import io.kite.annotations.Description;
import io.kite.annotations.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts Java types into {@link SchemaNode} trees suitable for LLM tool and output schemas.
 *
 * <p>Supported:
 * <ul>
 *   <li>Primitives and boxed primitives</li>
 *   <li>String, enum</li>
 *   <li>{@code java.time.{Instant, LocalDate, LocalDateTime, OffsetDateTime, ZonedDateTime}} → string/date-time</li>
 *   <li>Records (recursive — nested records become nested objects)</li>
 *   <li>{@code List<T>} → array</li>
 *   <li>{@code Optional<T>} → unwraps T and removes the field from {@code required}</li>
 * </ul>
 *
 * <p>Results are cached by {@link Class} forever. Callers must not mutate returned nodes.
 */
public final class JsonSchemaGenerator {

    private static final ConcurrentHashMap<Class<?>, SchemaNode> RECORD_CACHE = new ConcurrentHashMap<>();

    private JsonSchemaGenerator() {}

    /** Build (or return cached) schema for a record type. */
    public static SchemaNode forRecord(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException("forRecord requires a record type, got " + type.getName());
        }
        return RECORD_CACHE.computeIfAbsent(type, JsonSchemaGenerator::buildRecord);
    }

    /**
     * Build a schema for the parameters of a method. Parameters whose index is in
     * {@code excludeIndices} are skipped (used to drop the {@code @Ctx} parameter).
     *
     * <p>Parameter names come from {@link ToolParam#name()} if set, otherwise from
     * {@link java.lang.reflect.Parameter#getName()} which requires the {@code -parameters}
     * compile flag (enforced by the convention plugin).
     */
    public static SchemaNode forMethodParameters(Method method, java.util.Set<Integer> excludeIndices) {
        Map<String, SchemaNode> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        var params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (excludeIndices != null && excludeIndices.contains(i)) continue;
            var p = params[i];
            var tpAnn = p.getAnnotation(ToolParam.class);
            var descAnn = p.getAnnotation(Description.class);
            String name = (tpAnn != null && !tpAnn.name().isEmpty()) ? tpAnn.name() : p.getName();
            String description;
            if (tpAnn != null && !tpAnn.description().isEmpty()) description = tpAnn.description();
            else if (descAnn != null) description = descAnn.value();
            else description = null;
            boolean optional = (tpAnn != null && !tpAnn.required()) || isOptionalType(p.getParameterizedType());
            Type actual = unwrapOptional(p.getParameterizedType());
            props.put(name, buildWithDescription(actual, description));
            if (!optional) required.add(name);
        }
        return new SchemaNode.Obj(props, required, null, true);
    }

    /** Build a schema for an arbitrary type (used by the Tool programmatic builder). */
    public static SchemaNode forType(Type type) {
        return build(type);
    }

    private static SchemaNode buildRecord(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        Map<String, SchemaNode> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (var comp : components) {
            Description descAnn = comp.getAnnotation(Description.class);
            String description = descAnn == null ? null : descAnn.value();
            boolean optional = isOptionalType(comp.getGenericType());
            Type actual = unwrapOptional(comp.getGenericType());
            props.put(comp.getName(), buildWithDescription(actual, description));
            if (!optional) required.add(comp.getName());
        }
        String typeDescription = null;
        Description cls = type.getAnnotation(Description.class);
        if (cls != null) typeDescription = cls.value();
        return new SchemaNode.Obj(props, required, typeDescription, true);
    }

    private static SchemaNode buildWithDescription(Type type, String description) {
        SchemaNode node = build(type);
        if (description == null) return node;
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

    private static SchemaNode build(Type type) {
        if (type instanceof Class<?> c) return buildClass(c);
        if (type instanceof ParameterizedType pt) return buildParameterized(pt);
        throw new IllegalArgumentException("Unsupported schema type: " + type);
    }

    private static SchemaNode buildClass(Class<?> c) {
        if (c == String.class) return new SchemaNode.Str(null, null);
        if (c == int.class || c == Integer.class) return new SchemaNode.Int(null);
        if (c == long.class || c == Long.class) return new SchemaNode.Int(null);
        if (c == short.class || c == Short.class) return new SchemaNode.Int(null);
        if (c == byte.class || c == Byte.class) return new SchemaNode.Int(null);
        if (c == double.class || c == Double.class) return new SchemaNode.Num(null);
        if (c == float.class || c == Float.class) return new SchemaNode.Num(null);
        if (c == boolean.class || c == Boolean.class) return new SchemaNode.Bool(null);
        if (c == char.class || c == Character.class) return new SchemaNode.Str(null, null);
        if (c == Instant.class
                || c == LocalDateTime.class
                || c == OffsetDateTime.class
                || c == ZonedDateTime.class) return new SchemaNode.Str(null, "date-time");
        if (c == LocalDate.class) return new SchemaNode.Str(null, "date");
        if (c.isEnum()) {
            List<String> values = Arrays.stream(c.getEnumConstants()).map(Object::toString).toList();
            return new SchemaNode.Enumr(values, null);
        }
        if (c.isRecord()) return forRecord(c);
        if (c.isArray()) return new SchemaNode.Arr(build(c.getComponentType()), null);
        throw new IllegalArgumentException("Unsupported schema class: " + c.getName());
    }

    private static SchemaNode buildParameterized(ParameterizedType pt) {
        Type raw = pt.getRawType();
        if (raw instanceof Class<?> rc) {
            if (List.class.isAssignableFrom(rc) || Iterable.class.isAssignableFrom(rc)) {
                Type itemType = pt.getActualTypeArguments()[0];
                return new SchemaNode.Arr(build(itemType), null);
            }
            if (rc == Optional.class) {
                // Shouldn't normally happen because the caller unwraps Optional first, but be defensive.
                return build(pt.getActualTypeArguments()[0]);
            }
        }
        throw new IllegalArgumentException("Unsupported parameterized schema type: " + pt);
    }

    private static boolean isOptionalType(Type type) {
        if (type instanceof ParameterizedType pt && pt.getRawType() == Optional.class) return true;
        return false;
    }

    private static Type unwrapOptional(Type type) {
        if (type instanceof ParameterizedType pt && pt.getRawType() == Optional.class) {
            return pt.getActualTypeArguments()[0];
        }
        return type;
    }
}
