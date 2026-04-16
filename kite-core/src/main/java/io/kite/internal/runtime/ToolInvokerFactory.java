package io.kite.internal.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.kite.Tool;
import io.kite.annotations.Ctx;
import io.kite.annotations.Description;
import io.kite.annotations.ToolParam;
import io.kite.internal.json.JsonCodec;
import io.kite.schema.JsonSchemaGenerator;
import io.kite.schema.SchemaNode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scans a tools-bean for {@link io.kite.annotations.Tool @Tool}-annotated methods and compiles
 * them into immutable {@link Tool} instances. All reflection runs here, at agent build time.
 * The resulting {@link ToolInvoker}s use pre-bound {@link MethodHandle}s for invocation.
 */
public final class ToolInvokerFactory {

    private ToolInvokerFactory() {}

    public static List<Tool> scan(Object bean, Class<?> expectedContextType) {
        if (bean == null) throw new IllegalArgumentException("tools(bean) — bean must not be null");
        Class<?> beanClass = bean.getClass();
        List<Method> annotated = Arrays.stream(beanClass.getMethods())
                .filter(m -> m.isAnnotationPresent(io.kite.annotations.Tool.class))
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .collect(Collectors.toList());
        if (annotated.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tools bean " + beanClass.getName() + " has no @Tool-annotated methods");
        }
        List<Tool> tools = new ArrayList<>(annotated.size());
        for (Method m : annotated) {
            tools.add(buildOne(bean, m, expectedContextType));
        }
        return List.copyOf(tools);
    }

    private static Tool buildOne(Object bean, Method method, Class<?> expectedContextType) {
        var ann = method.getAnnotation(io.kite.annotations.Tool.class);
        String name = ann.name().isEmpty() ? method.getName() : ann.name();
        String description;
        if (!ann.description().isEmpty()) {
            description = ann.description();
        } else {
            Description desc = method.getAnnotation(Description.class);
            description = desc == null ? "" : desc.value();
        }

        Parameter[] parameters = method.getParameters();
        int ctxIndex = -1;
        Class<?> ctxType = null;
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(Ctx.class)) {
                if (ctxIndex >= 0) {
                    throw new IllegalArgumentException(
                            "@Tool method " + method + " has more than one @Ctx parameter");
                }
                ctxIndex = i;
                ctxType = parameters[i].getType();
            }
        }

        if (ctxIndex >= 0) {
            if (expectedContextType == null || expectedContextType == Void.class) {
                throw new IllegalArgumentException(
                        "@Tool method " + method + " declares @Ctx " + ctxType.getName()
                                + " but its agent has no context type. Use Agent.builder(ctxType).");
            }
            if (!ctxType.isAssignableFrom(expectedContextType) && !expectedContextType.isAssignableFrom(ctxType)) {
                throw new IllegalArgumentException(
                        "@Tool method " + method + " declares @Ctx " + ctxType.getName()
                                + " which is not compatible with agent context type " + expectedContextType.getName());
            }
        }

        Set<Integer> excluded = ctxIndex >= 0 ? Set.of(ctxIndex) : new HashSet<>();
        SchemaNode schema = JsonSchemaGenerator.forMethodParameters(method, excluded);

        MethodHandle handle;
        try {
            handle = MethodHandles.lookup().unreflect(method).bindTo(bean);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot access @Tool method " + method + " — make it public", e);
        }

        Class<?>[] paramTypes = method.getParameterTypes();
        String[] paramNames = new String[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            ToolParam tp = parameters[i].getAnnotation(ToolParam.class);
            if (tp != null && !tp.name().isEmpty()) {
                paramNames[i] = tp.name();
            } else {
                paramNames[i] = parameters[i].getName();
            }
        }

        int ctxIdxFinal = ctxIndex;
        ToolInvoker invoker = new MethodHandleInvoker(name, handle, paramTypes, paramNames, ctxIdxFinal);
        boolean usesContext = ctxIndex >= 0;
        return new Tool(name, description, schema, invoker, usesContext, Tool.Kind.FUNCTION, null, null);
    }

    /** ToolInvoker backed by a method handle bound to a bean instance. */
    private static final class MethodHandleInvoker implements ToolInvoker {
        private final String name;
        private final MethodHandle handle;
        private final Class<?>[] paramTypes;
        private final String[] paramNames;
        private final int ctxIndex;

        MethodHandleInvoker(String name, MethodHandle handle, Class<?>[] paramTypes, String[] paramNames, int ctxIndex) {
            this.name = name;
            this.handle = handle;
            this.paramTypes = paramTypes;
            this.paramNames = paramNames;
            this.ctxIndex = ctxIndex;
        }

        @Override
        public String invoke(Object context, String argsJson) throws Exception {
            JsonNode node = JsonCodec.shared().readTreeOrEmpty(argsJson);
            Object[] callArgs = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                if (i == ctxIndex) {
                    callArgs[i] = context;
                    continue;
                }
                JsonNode field = node.get(paramNames[i]);
                if (field == null || field.isNull()) {
                    callArgs[i] = defaultFor(paramTypes[i]);
                } else {
                    callArgs[i] = JsonCodec.shared().treeToValue(field, paramTypes[i]);
                }
            }
            Object result;
            try {
                result = handle.invokeWithArguments(callArgs);
            } catch (Throwable t) {
                throw new ToolExecutionException("Tool '" + name + "' threw: " + t.getMessage(), t);
            }
            if (result == null) return "null";
            if (result instanceof String s) return JsonCodec.shared().writeValueAsString(s);
            return JsonCodec.shared().writeValueAsString(result);
        }

        private static Object defaultFor(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return Boolean.FALSE;
            if (type == char.class) return (char) 0;
            if (type == int.class || type == short.class || type == byte.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0.0;
            return null;
        }
    }

    public static final class ToolExecutionException extends RuntimeException {
        public ToolExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
