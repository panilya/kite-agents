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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Converts Java types into {@link SchemaNode} trees suitable for LLM tool and output schemas.
 *
 * <p>Supported:
 * <ul>
 *   <li>Primitives and boxed primitives</li>
 *   <li>String, enum</li>
 *   <li>{@code java.time.{Instant, LocalDate, LocalDateTime, OffsetDateTime, ZonedDateTime}} → string/date-time</li>
 *   <li>Records (recursive — nested records become nested objects)</li>
 *   <li>{@code List<T>}, {@code Iterable<T>} → array</li>
 *   <li>{@code Optional<T>} as a <em>record field</em> → unwraps T and removes the field from {@code required}.
 *       {@code Optional<T>} is <em>not</em> accepted as a tool-method parameter type — use
 *       {@code @ToolParam(required = false)} on a plain-typed parameter instead.</li>
 * </ul>
 *
 * <p>Results are cached by {@link Class} forever. Callers must not mutate returned nodes.
 */
public final class JsonSchemaGenerator {

    private static final ConcurrentHashMap<Class<?>, SchemaNode> RECORD_CACHE = new ConcurrentHashMap<>();

    private static final SchemaNode STRING  = new SchemaNode.Str(null, null, false);
    private static final SchemaNode BOOL    = new SchemaNode.Bool(null, false);
    private static final SchemaNode INTEGER = new SchemaNode.Int(null, false);
    private static final SchemaNode NUMBER  = new SchemaNode.Num(null, false);
    private static final SchemaNode DATE_TIME = new SchemaNode.Str(null, "date-time", false);
    private static final SchemaNode DATE      = new SchemaNode.Str(null, "date", false);

    private static final Map<Class<?>, SchemaNode> LEAF_SCHEMAS = Map.ofEntries(
            Map.entry(String.class,    STRING),
            Map.entry(char.class,      STRING),
            Map.entry(Character.class, STRING),
            Map.entry(boolean.class,   BOOL),
            Map.entry(Boolean.class,   BOOL),
            Map.entry(int.class,       INTEGER),
            Map.entry(Integer.class,   INTEGER),
            Map.entry(long.class,      INTEGER),
            Map.entry(Long.class,      INTEGER),
            Map.entry(short.class,     INTEGER),
            Map.entry(Short.class,     INTEGER),
            Map.entry(byte.class,      INTEGER),
            Map.entry(Byte.class,      INTEGER),
            Map.entry(double.class,    NUMBER),
            Map.entry(Double.class,    NUMBER),
            Map.entry(float.class,     NUMBER),
            Map.entry(Float.class,     NUMBER),
            Map.entry(Instant.class,        DATE_TIME),
            Map.entry(LocalDateTime.class,  DATE_TIME),
            Map.entry(OffsetDateTime.class, DATE_TIME),
            Map.entry(ZonedDateTime.class,  DATE_TIME),
            Map.entry(LocalDate.class,      DATE));

    private JsonSchemaGenerator() {}

    /** Build (or return cached) schema for a record type. */
    public static SchemaNode forRecord(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException("forRecord requires a record type, got " + type.getName());
        }
        SchemaNode cached = RECORD_CACHE.get(type);
        if (cached != null) return cached;
        return forRecordCached(type, new LinkedHashSet<>());
    }

    private static SchemaNode forRecordCached(Class<?> type, LinkedHashSet<Class<?>> inFlight) {
        SchemaNode cached = RECORD_CACHE.get(type);
        if (cached != null) return cached;
        if (!inFlight.add(type)) {
            String cycle = inFlight.stream().map(Class::getSimpleName).collect(Collectors.joining(" → "))
                    + " → " + type.getSimpleName();
            throw new IllegalArgumentException(
                    "Recursive record not supported: " + type.getName() + " (cycle: " + cycle + ")");
        }
        try {
            SchemaNode built = buildRecord(type, inFlight);
            SchemaNode prior = RECORD_CACHE.putIfAbsent(type, built);
            return prior != null ? prior : built;
        } finally {
            inFlight.remove(type);
        }
    }

    /**
     * Build a schema for the parameters of a method. Parameters whose index is in
     * {@code excludeIndices} are skipped (used to drop the {@code @Ctx} parameter).
     *
     * <p>Parameter names come from {@link ToolParam#name()} if set, otherwise from
     * {@link java.lang.reflect.Parameter#getName()} which requires the {@code -parameters}
     * compile flag (enforced by the convention plugin).
     */
    public static SchemaNode forMethodParameters(Method method, Set<Integer> excludeIndices) {
        LinkedHashSet<Class<?>> inFlight = new LinkedHashSet<>();
        Map<String, SchemaNode> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        var params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (excludeIndices != null && excludeIndices.contains(i)) continue;
            var p = params[i];
            var tp = p.getAnnotation(ToolParam.class);
            var desc = p.getAnnotation(Description.class);
            String name = (tp != null && !tp.name().isEmpty()) ? tp.name() : p.getName();
            String description = resolveDescription(tp, desc);
            Type paramType = p.getParameterizedType();
            if (paramType instanceof ParameterizedType pt && pt.getRawType() == Optional.class) {
                throw new IllegalArgumentException(
                        "Optional<T> is not supported as a tool parameter type on "
                                + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                                + "(... " + name + "). Declare a plain-typed parameter with "
                                + "@ToolParam(required = false) and check for null.");
            }
            boolean explicitlyOptional = tp != null && !tp.required();
            props.put(name, withDescription(build(paramType, inFlight), description));
            if (!explicitlyOptional) required.add(name);
        }
        return new SchemaNode.Obj(props, required, null, true);
    }

    /** Build a schema for an arbitrary type (used by the Tool programmatic builder). */
    public static SchemaNode forType(Type type) {
        return build(type, new LinkedHashSet<>());
    }

    private static SchemaNode buildRecord(Class<?> type, LinkedHashSet<Class<?>> inFlight) {
        RecordComponent[] components = type.getRecordComponents();
        Map<String, SchemaNode> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (var comp : components) {
            Description desc = comp.getAnnotation(Description.class);
            String description = desc == null ? null : desc.value();
            var u = unwrap(comp.getGenericType());
            props.put(comp.getName(), withDescription(build(u.type(), inFlight), description));
            if (!u.wasOptional()) required.add(comp.getName());
        }
        Description cls = type.getAnnotation(Description.class);
        String typeDescription = cls == null ? null : cls.value();
        return new SchemaNode.Obj(props, required, typeDescription, true);
    }

    private static SchemaNode build(Type type, LinkedHashSet<Class<?>> inFlight) {
        if (type instanceof Class<?> c) return buildClass(c, inFlight);
        if (type instanceof ParameterizedType pt) return buildParameterized(pt, inFlight);
        throw new IllegalArgumentException("Unsupported schema type: " + type);
    }

    private static SchemaNode buildClass(Class<?> c, LinkedHashSet<Class<?>> inFlight) {
        SchemaNode leaf = LEAF_SCHEMAS.get(c);
        if (leaf != null) return leaf;
        if (c.isEnum())   return buildEnum(c);
        if (c.isRecord()) return forRecordCached(c, inFlight);
        if (c.isArray())  return new SchemaNode.Arr(build(c.getComponentType(), inFlight), null);
        throw new IllegalArgumentException("Unsupported schema class: " + c.getName());
    }

    private static SchemaNode buildEnum(Class<?> c) {
        List<String> values = Arrays.stream(c.getEnumConstants())
                .map(e -> ((Enum<?>) e).name())
                .toList();
        return new SchemaNode.Enumr(values, null, false);
    }

    private static SchemaNode buildParameterized(ParameterizedType pt, LinkedHashSet<Class<?>> inFlight) {
        if (!(pt.getRawType() instanceof Class<?> rc)) {
            throw new IllegalArgumentException("Unsupported parameterized schema type: " + pt);
        }
        if (Iterable.class.isAssignableFrom(rc)) {
            return new SchemaNode.Arr(build(pt.getActualTypeArguments()[0], inFlight), null);
        }
        throw new IllegalArgumentException("Unsupported parameterized schema type: " + pt);
    }

    private static SchemaNode withDescription(SchemaNode node, String description) {
        return description == null ? node : node.withDescription(description);
    }

    private static String resolveDescription(ToolParam tp, Description desc) {
        if (tp != null && !tp.description().isEmpty()) return tp.description();
        if (desc != null) return desc.value();
        return null;
    }

    private record Unwrapped(Type type, boolean wasOptional) {}

    private static Unwrapped unwrap(Type t) {
        if (t instanceof ParameterizedType pt && pt.getRawType() == Optional.class) {
            return new Unwrapped(pt.getActualTypeArguments()[0], true);
        }
        return new Unwrapped(t, false);
    }
}
