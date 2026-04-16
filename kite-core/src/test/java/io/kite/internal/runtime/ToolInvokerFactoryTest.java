package io.kite.internal.runtime;

import io.kite.Tool;
import io.kite.annotations.Ctx;
import io.kite.annotations.ToolParam;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolInvokerFactoryTest {

    record SupportCtx(String customerId) {}

    public static final class RefundTools {
        String lastCall;

        @io.kite.annotations.Tool(description = "Issue a refund")
        public String refund(
                @Ctx SupportCtx ctx,
                @ToolParam(description = "Order ID") String orderId,
                @ToolParam(description = "Amount in USD") double amount) {
            lastCall = ctx.customerId() + ":" + orderId + ":" + amount;
            return "refunded";
        }
    }

    public static final class SimpleTools {
        @io.kite.annotations.Tool(name = "add", description = "Add two numbers")
        public int add(@ToolParam int a, @ToolParam int b) {
            return a + b;
        }
    }

    public static final class NoAnnotations {
        public String foo() { return "bar"; }
    }

    @Test
    void scansAnnotatedMethodAndGeneratesSchema() {
        var bean = new RefundTools();
        List<Tool> tools = ToolInvokerFactory.scan(bean, SupportCtx.class);
        assertThat(tools).hasSize(1);
        Tool t = tools.get(0);
        assertThat(t.name()).isEqualTo("refund");
        assertThat(t.description()).isEqualTo("Issue a refund");
        assertThat(t.usesContext()).isTrue();
        String schema = t.paramsSchemaJson();
        assertThat(schema).contains("\"orderId\"");
        assertThat(schema).contains("\"amount\"");
        // @Ctx parameter is NOT in the schema
        assertThat(schema).doesNotContain("customerId");
    }

    @Test
    void invokesMethodWithContextAndArgs() throws Exception {
        var bean = new RefundTools();
        Tool t = ToolInvokerFactory.scan(bean, SupportCtx.class).get(0);
        String result = t.invoker().invoke(new SupportCtx("C-42"), "{\"orderId\":\"#1234\",\"amount\":29.99}");
        assertThat(result).isEqualTo("\"refunded\"");
        assertThat(bean.lastCall).isEqualTo("C-42:#1234:29.99");
    }

    @Test
    void simpleToolWithoutContext() throws Exception {
        var bean = new SimpleTools();
        Tool t = ToolInvokerFactory.scan(bean, null).get(0);
        assertThat(t.usesContext()).isFalse();
        String result = t.invoker().invoke(null, "{\"a\":2,\"b\":3}");
        assertThat(result).isEqualTo("5");
    }

    @Test
    void rejectsBeanWithoutAnnotations() {
        assertThatThrownBy(() -> ToolInvokerFactory.scan(new NoAnnotations(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no @Tool-annotated methods");
    }
}
