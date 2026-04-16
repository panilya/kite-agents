package io.kite.samples;

import io.kite.Agent;
import io.kite.Kite;
import io.kite.annotations.Tool;
import io.kite.annotations.ToolParam;
import io.kite.openai.OpenAiProvider;

/**
 * Tool calling: any plain object with {@code @Tool}-annotated methods can be
 * handed to {@code .tools(bean)} and Kite exposes each method to the LLM as a
 * callable tool. The runtime drives the tool-call loop — the sample just
 * prints the final reply.
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class CalculatorAgent {

    public static final class Calculator {
        @Tool(description = "Add two integers and return the sum")
        public int add(
                @ToolParam(description = "First addend") int a,
                @ToolParam(description = "Second addend") int b) {
            return a + b;
        }

        @Tool(description = "Multiply two integers and return the product")
        public int multiply(
                @ToolParam(description = "First factor") int a,
                @ToolParam(description = "Second factor") int b) {
            return a * b;
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

            var agent = Agent.builder()
                    .model("gpt-4o-mini")
                    .name("calculator")
                    .instructions("You are a calculator. Always use the tools to compute results.")
                    .tools(new Calculator())
                    .build();

            var reply = kite.run(agent, "What is (12 + 8) times 3?");
            System.out.println(reply.text());
        }
    }
}
