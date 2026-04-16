package io.kite.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tool-method parameter as receiving the agent's typed context. Excluded from the JSON
 * schema shown to the LLM and injected by Kite at invocation time. The parameter type must
 * match the agent's declared context type or a superclass of it; this is validated at
 * agent build time.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Ctx {
}
