package io.kite.anthropic;

import io.kite.Agent;
import io.kite.Kite;
import io.kite.Reply;
import io.kite.Status;
import io.kite.annotations.Description;
import io.kite.annotations.Tool;
import io.kite.annotations.ToolParam;
import io.kite.tracing.Tracing;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke tests against the real Anthropic Messages API. Gated on
 * {@code ANTHROPIC_API_KEY} and the {@code live} JUnit tag — run via
 * {@code ./gradlew :kite-anthropic:liveTest}. Uses the cheapest model.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AnthropicLiveSmokeTest {

    private static final String MODEL = "claude-haiku-4-5";

    public static final class Adder {
        @Tool(description = "Add two integers")
        public int add(
                @ToolParam(description = "First addend") int a,
                @ToolParam(description = "Second addend") int b) {
            return a + b;
        }
    }

    record Booking(
            @Description("IATA airline code") String airline,
            @Description("Flight number") String flightNumber) {}

    @Test
    void text() {
        try (var kite = Kite.builder()
                .provider(new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")))
                .tracing(Tracing.off())
                .build()) {
            var agent = Agent.builder()
                    .model(MODEL)
                    .instructions("Reply with a single short sentence.")
                    .build();
            Reply r = kite.run(agent, "Say hello.");
            assertThat(r.status()).isEqualTo(Status.OK);
            assertThat(r.text()).isNotBlank();
        }
    }

    @Test
    void toolCallRoundTrip() {
        try (var kite = Kite.builder()
                .provider(new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")))
                .tracing(Tracing.off())
                .build()) {
            var agent = Agent.builder()
                    .model(MODEL)
                    .instructions("Use the add tool for any arithmetic; don't compute yourself.")
                    .tools(new Adder())
                    .build();
            Reply r = kite.run(agent, "What is 17 plus 25?");
            assertThat(r.status()).isEqualTo(Status.OK);
            assertThat(r.text()).contains("42");
        }
    }

    @Test
    void structuredOutput() {
        try (var kite = Kite.builder()
                .provider(new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")))
                .tracing(Tracing.off())
                .build()) {
            var agent = Agent.builder()
                    .model(MODEL)
                    .instructions("Extract the booking details.")
                    .output(Booking.class)
                    .build();
            Reply r = kite.run(agent, "United UA123.");
            assertThat(r.status()).isEqualTo(Status.OK);
            Booking b = r.output(Booking.class);
            assertThat(b).isNotNull();
            assertThat(b.airline()).isNotBlank();
            assertThat(b.flightNumber()).isNotBlank();
        }
    }
}
