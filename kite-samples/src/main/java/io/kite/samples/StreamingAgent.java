package io.kite.samples;

import io.kite.Agent;
import io.kite.Event;
import io.kite.Kite;
import io.kite.openai.OpenAiProvider;

/**
 * Streaming: handle events as they arrive from the model via
 * {@code kite.stream(...)}. {@link Event} is a sealed interface so a
 * pattern-matching {@code switch} is exhaustive at compile time.
 *
 * <p>This sample handles the two most common variants — {@code Delta}
 * (incremental text from the model) and {@code Done} (terminal marker
 * carrying the accumulated {@code Reply}).
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class StreamingAgent {

    public static void main(String[] args) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null) {
            System.err.println("Set OPENAI_API_KEY to run this sample.");
            return;
        }

        try (var kite = Kite.builder()
                .provider(new OpenAiProvider(key))
                .build()) {

            var agent = Agent.of("gpt-4o-mini")
                    .name("storyteller")
                    .instructions("You are a storyteller. Keep responses under three sentences.")
                    .build();

            kite.stream(agent, "Tell me a tiny story about a kite.", event -> {
                switch (event) {
                    case Event.Delta d -> System.out.print(d.text());
                    case Event.Done done -> {
                        System.out.println();
                        var usage = done.reply().usage();
                        System.out.println("-- " + usage.totalTokens() + " tokens --");
                    }
                    default -> {
                        // Ignored in this sample: ToolCall, ToolResult, Transfer, Blocked, Error.
                    }
                }
            });
        }
    }
}
