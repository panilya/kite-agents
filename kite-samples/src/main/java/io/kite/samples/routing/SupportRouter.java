package io.kite.samples.routing;

import io.kite.Agent;
import io.kite.Kite;
import io.kite.annotations.Ctx;
import io.kite.annotations.Tool;
import io.kite.annotations.ToolParam;
import io.kite.openai.OpenAiProvider;

/**
 * Multi-agent routing with a typed context.
 *
 * <p>A triage agent inspects the user's message and transfers control to one
 * of two specialist agents. All three agents share the same
 * {@link SupportCtx}, enforced at compile time by {@code Agent<SupportCtx>}.
 * The billing tool receives the context via {@code @Ctx}; Kite hides the
 * {@code ctx} parameter from the LLM's tool schema and injects it at call
 * time.
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class SupportRouter {

    public record SupportCtx(String customerId, String plan) {}

    public static final class BillingTools {
        @Tool(description = "Refund an order for the current customer")
        public String refund(
                @Ctx SupportCtx ctx,
                @ToolParam(description = "Order identifier") String orderId) {
            // A real implementation would call a payments service.
            return "Refunded order " + orderId + " for customer " + ctx.customerId();
        }
    }

    public static void main(String[] args) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null) {
            System.err.println("Set OPENAI_API_KEY to run this sample.");
            return;
        }

        try (var kite = Kite.builder()
                .provider(new OpenAiProvider(key))
                .build()) {

            var billing = Agent.builder(SupportCtx.class)
                    .model("gpt-4o-mini")
                    .name("billing")
                    .description("Handles billing, payments, refunds, and invoices")
                    .instructions("You are a billing specialist. Use the refund tool when appropriate.")
                    .tools(new BillingTools())
                    .build();

            var technical = Agent.builder(SupportCtx.class)
                    .model("gpt-4o-mini")
                    .name("technical")
                    .description("Handles bugs, crashes, and other technical issues")
                    .instructions("You are a technical-support specialist. Ask for the app version and OS.")
                    .build();

            var triage = Agent.builder(SupportCtx.class)
                    .model("gpt-4o-mini")
                    .name("triage")
                    .instructions(ctx -> "You triage support tickets for customer "
                            + ctx.customerId() + " (plan: " + ctx.plan()
                            + "). Route to the best specialist for the request.")
                    .route(billing)
                    .route(technical)
                    .build();

            var ctx = new SupportCtx("C-42", "premium");
            var reply = kite.run(triage,
                    "I was charged twice for order #1234. Please refund one of the charges.",
                    ctx);

            System.out.println("Final agent: " + reply.agent().name());
            System.out.println(reply.text());
        }
    }
}
