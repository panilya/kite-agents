package io.kite.samples;

import io.kite.Agent;
import io.kite.Kite;
import io.kite.annotations.Description;
import io.kite.openai.OpenAiProvider;

/**
 * Structured output: the agent returns a typed record instead of free-form
 * text. The JSON schema is generated from the record at agent build time and
 * sent to the model; {@code @Description} values appear in the schema so the
 * LLM knows what each field means.
 *
 * <p>Run with {@code OPENAI_API_KEY} set.
 */
public final class BookingExtractor {

    public record BookingDetails(
            @Description("IATA airline code, e.g. UA or BA") String airline,
            @Description("Flight number such as UA123") String flightNumber,
            @Description("Origin airport or city") String origin,
            @Description("Destination airport or city") String destination,
            @Description("Total ticket price in USD") double priceUsd) {}

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
                    .name("booking-extractor")
                    .instructions("Extract the booking details from the user's message.")
                    .output(BookingDetails.class)
                    .build();

            var reply = kite.run(agent,
                    "Book me United flight UA123 from New York to London for $849.50.");

            BookingDetails booking = reply.output(BookingDetails.class);
            System.out.println(booking);
        }
    }
}
