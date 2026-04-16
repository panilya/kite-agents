package io.kite.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Human-readable description attached to a record component, type, or parameter. Picked up by
 * the JSON schema generator and included in the schema shown to the LLM.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.TYPE, ElementType.PARAMETER, ElementType.FIELD})
public @interface Description {
    String value();
}
