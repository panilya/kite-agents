package io.kite.internal.runtime;

import io.kite.annotations.Tool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MissingParametersFlagTest {

    /**
     * Synthetic class that simulates compilation without {@code -parameters}: the LLM-facing
     * parameter has a synthetic name. We can't easily turn off {@code -parameters} per-test, so
     * instead we feed in a method whose parameter happens to be literally named {@code arg0}
     * (the same name javac would emit).
     */
    public static final class BadlyNamed {
        @Tool(description = "Echo")
        public String echo(String arg0) {
            return arg0;
        }
    }

    @Test
    void throwsWhenParameterNameLooksSynthetic() {
        // Note: this only triggers because the user (or a missing -parameters flag) gave the
        // parameter a name that matches the arg\d+ pattern. This is the same string javac would
        // emit without -parameters, so the check catches both cases.
        assertThatThrownBy(() -> ToolInvokerFactory.scan(new BadlyNamed(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("synthetic name 'arg0'")
                .hasMessageContaining("-parameters");
    }
}
