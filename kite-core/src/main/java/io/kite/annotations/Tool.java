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

    /**
     * Marks the tool as having no externally-observable side effects. Kite may then start it
     * in parallel with any still-running parallel input guards (instead of waiting for guards
     * to pass first), overlapping the tool's latency with the guard wait. If a guard blocks,
     * the result is thrown away and nothing leaks to the caller. Defaults to false.
     */
    boolean readOnly() default false;
}
