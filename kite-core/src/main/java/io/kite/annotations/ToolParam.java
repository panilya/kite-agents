package io.kite.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Metadata for a parameter of a {@link Tool}-annotated method. Optional — when omitted, the
 * parameter's Java name (from the {@code -parameters} compile flag) and type alone are used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {
    /** Override for the parameter name in the JSON schema. */
    String name() default "";

    /** Description shown to the LLM. */
    String description() default "";

    /** Whether the LLM must provide this parameter. Default true. */
    boolean required() default true;
}
