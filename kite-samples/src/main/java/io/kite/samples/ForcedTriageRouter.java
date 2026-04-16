package io.kite.samples;

import io.kite.Agent;
import io.kite.Kite;
import io.kite.ToolChoice;
import io.kite.annotations.Ctx;
import io.kite.annotations.Tool;
import io.kite.annotations.ToolParam;
import io.kite.openai.OpenAiProvider;

/**
 * Forced routing in a multi-agent triage system.
 *
 * <p>The triage agent is configured with {@code ToolChoice.required()} and has only
 * routes (no function tools), so the model is forced to call one of the
 * {@code transfer_to_*} tools on every triage turn. Triage can no longer respond
 * directly to the user — it must hand off to a specialist.
 *
 * <p>After the transfer, the target agent ({@code billing} or {@code technical}) runs
 * with <em>its own</em> tool-choice policy — here just the default {@code auto()} —
 * so the forced-routing directive does not propagate. This demonstrates Kite's
 * multi-agent semantics: each agent owns its directive, and directives survive
 * route transfers without bleeding across agent boundaries.
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class ForcedTriageRouter {

    public record SupportCtx(String customerId, String plan) {}

    public static final class BillingTools {
        @Tool(description = "Refund an order for the current customer")
        public String refund(
                @Ctx SupportCtx ctx,
                @ToolParam(description = "Order identifier") String orderId) {
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
                    .instructions("You are a billing specialist. Use the refund tool when needed.")
                    .tools(new BillingTools())
                    // No toolChoice here — billing runs on auto(), free to call the
                    // refund tool or respond directly. This proves triage's required()
                    // does not propagate across the route transfer.
                    .build();

            var technical = Agent.builder(SupportCtx.class)
                    .model("gpt-4o-mini")
                    .name("technical")
                    .description("Handles bugs, crashes, and other technical issues")
                    .instructions("You are a technical-support specialist.")
                    .build();

            var triage = Agent.builder(SupportCtx.class)
                    .model("gpt-4o-mini")
                    .name("triage")
                    .instructions(ctx -> "Triage the request for customer "
                            + ctx.customerId() + " and transfer to the right specialist.")
                    .route(billing)
                    .route(technical)
                    // Force routing: triage must call one of the transfer_to_* tools,
                    // it cannot answer the user directly.
                    .toolChoice(ToolChoice.required())
                    .maxTurns(6)
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
