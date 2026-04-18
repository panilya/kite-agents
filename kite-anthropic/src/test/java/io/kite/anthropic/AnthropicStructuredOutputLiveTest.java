package io.kite.anthropic;

import io.kite.Agent;
import io.kite.Kite;
import io.kite.Reply;
import io.kite.Status;
import io.kite.annotations.Description;
import io.kite.tracing.Tracing;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live round-trip tests for structured outputs against Anthropic — schema emission →
 * {@code output_config} acceptance → response JSON → record materialization.
 *
 * <p>Gated on {@code ANTHROPIC_API_KEY} and the {@code live} JUnit tag.
 * Run with {@code ./gradlew :kite-anthropic:liveTest}. Cheapest model.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AnthropicStructuredOutputLiveTest {

    private static final String MODEL = "claude-haiku-4-5";

    private static Kite kite() {
        return Kite.builder()
                .provider(new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")))
                .tracing(Tracing.off())
                .build();
    }

    // ---------- primitives ----------

    record Primitives(int i, long l, double d, boolean b) {}

    @Test
    void allPrimitives() {
        try (var kite = kite()) {
            Reply r = kite.run(Agent.builder()
                    .model(MODEL)
                    .instructions("Extract the values the user provides.")
                    .output(Primitives.class)
                    .build(), "i=7, l=10000000000, d=3.14, b=true");
            assertThat(r.status()).isEqualTo(Status.OK);
            Primitives p = r.output(Primitives.class);
            assertThat(p.i()).isEqualTo(7);
            assertThat(p.l()).isEqualTo(10_000_000_000L);
            assertThat(p.d()).isEqualTo(3.14);
            assertThat(p.b()).isTrue();
        }
    }

    // ---------- Optional ----------

    record WithOptional(
            @Description("Passenger's full name") String name,
            @Description("Seat number if provided; otherwise omit") Optional<String> seat) {}

    @Test
    void optionalPopulated() {
        try (var kite = kite()) {
            Reply r = kite.run(Agent.builder()
                    .model(MODEL)
                    .instructions("Extract the passenger record.")
                    .output(WithOptional.class)
                    .build(), "Name: Alice Smith. Seat: 14C.");
            WithOptional o = r.output(WithOptional.class);
            assertThat(o.name()).containsIgnoringCase("Alice");
            assertThat(o.seat()).isPresent();
            assertThat(o.seat().orElseThrow()).containsIgnoringCase("14C");
        }
    }

    @Test
    void optionalAbsentReturnsEmpty() {
        try (var kite = kite()) {
            Reply r = kite.run(Agent.builder()
                    .model(MODEL)
                    .instructions("Extract the passenger record. "
                            + "If the user does not mention a seat, leave it null — do not invent one.")
                    .output(WithOptional.class)
                    .build(), "Name: Bob Jones.");
            WithOptional o = r.output(WithOptional.class);
            assertThat(o.name()).containsIgnoringCase("Bob");
            assertThat(o.seat()).isEmpty();
        }
    }

    // ---------- enum ----------

    enum Tier { FREE, PRO, ENTERPRISE }
    record WithEnum(String accountName, Tier tier) {}

    @Test
    void enumField() {
        try (var kite = kite()) {
            Reply r = kite.run(Agent.builder()
                    .model(MODEL)
                    .instructions("Classify the account. Tier must be FREE, PRO, or ENTERPRISE.")
                    .output(WithEnum.class)
                    .build(), "The Acme Co. account is on our top-tier enterprise plan.");
            WithEnum w = r.output(WithEnum.class);
            assertThat(w.accountName()).containsIgnoringCase("Acme");
            assertThat(w.tier()).isEqualTo(Tier.ENTERPRISE);
        }
    }

    // ---------- List<String> ----------

    record WithList(String subject, List<String> tags) {}

    @Test
    void listOfStrings() {
        try (var kite = kite()) {
            Reply r = kite.run(Agent.builder()
                    .model(MODEL)
                    .instructions("Summarize the message and extract tags as a list of lowercase keywords.")
                    .output(WithList.class)
                    .build(), "Java 21 records and pattern matching make schema generation easy.");
            WithList w = r.output(WithList.class);
            assertThat(w.subject()).isNotBlank();
            assertThat(w.tags()).isNotEmpty();
        }
    }

    // ---------- nested record ----------

    record Address(String city, String country) {}
    record Person(String name, Address address) {}

    @Test
    void nestedRecord() {
        try (var kite = kite()) {
            Reply r = kite.run(Agent.builder()
                    .model(MODEL)
                    .instructions("Extract the person and their address.")
                    .output(Person.class)
                    .build(), "Jane Doe lives in Berlin, Germany.");
            Person p = r.output(Person.class);
            assertThat(p.name()).containsIgnoringCase("Jane");
            assertThat(p.address().city()).containsIgnoringCase("Berlin");
            assertThat(p.address().country()).containsIgnoringCase("Germany");
        }
    }

    // ---------- List<Record> with nested Optional ----------

    record Passenger(String name, Optional<String> seat) {}
    record Manifest(String flightNumber, List<Passenger> passengers) {}

    @Test
    void listOfRecordsWithNestedOptional() {
        try (var kite = kite()) {
            Reply r = kite.run(Agent.builder()
                    .model(MODEL)
                    .instructions("Extract the flight manifest. "
                            + "If a passenger's seat is not mentioned, leave it null.")
                    .output(Manifest.class)
                    .build(),
                    "Flight UA123: Alice in 14C, Bob (no seat yet), Carol in 14D.");
            Manifest m = r.output(Manifest.class);
            assertThat(m.flightNumber()).containsIgnoringCase("UA123");
            assertThat(m.passengers()).hasSize(3);
            Passenger alice = m.passengers().stream()
                    .filter(p -> p.name().toLowerCase().contains("alice")).findFirst().orElseThrow();
            assertThat(alice.seat()).isPresent();
            Passenger bob = m.passengers().stream()
                    .filter(p -> p.name().toLowerCase().contains("bob")).findFirst().orElseThrow();
            assertThat(bob.seat()).isEmpty();
        }
    }

    // ---------- date/time ----------

    record Event(String title, LocalDate date, Instant createdAt) {}

    @Test
    void dateTimeTypes() {
        try (var kite = kite()) {
            Reply r = kite.run(Agent.builder()
                    .model(MODEL)
                    .instructions("Extract the event. "
                            + "date is ISO-8601 date (YYYY-MM-DD). "
                            + "createdAt is ISO-8601 instant (YYYY-MM-DDTHH:MM:SSZ).")
                    .output(Event.class)
                    .build(),
                    "\"KubeCon\" takes place on 2026-03-15. The record was created at 2026-01-02T09:30:00Z.");
            Event e = r.output(Event.class);
            assertThat(e.title()).containsIgnoringCase("KubeCon");
            assertThat(e.date()).isEqualTo(LocalDate.of(2026, 3, 15));
            assertThat(e.createdAt()).isEqualTo(Instant.parse("2026-01-02T09:30:00Z"));
        }
    }
}
