package io.kite.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method on a tools-bean as callable by an Agent's LLM. Scanned at agent build time by
 * {@link AgentBuilder#tools(Object)}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {
    /** Tool name exposed to the LLM. Defaults to the method name. */
    String name() default "";

    /** Description shown to the LLM in the tool schema. */
    String description() default "";
}
