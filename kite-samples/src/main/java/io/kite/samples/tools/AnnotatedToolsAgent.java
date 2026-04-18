package io.kite.samples.tools;

import io.kite.Agent;
import io.kite.Kite;
import io.kite.anthropic.AnthropicProvider;
import io.kite.annotations.Tool;
import io.kite.annotations.ToolParam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Annotated-bean tools: expose any POJO's methods to the LLM by marking them with
 * {@link Tool @Tool}. Kite scans the bean, builds a JSON schema from each method's
 * signature, and wires every method up as a callable tool.
 *
 * <p>The sample is a tiny in-memory reminder service. A couple of parameters are
 * optional, declared via {@link ToolParam#required() @ToolParam(required = false)};
 * the runtime passes {@code null} for any the model chooses to omit.
 *
 * <p>Run with {@code ANTHROPIC_API_KEY} set.
 */
public final class AnnotatedToolsAgent {

    public record Reminder(String id, String title, String priority, Instant createdAt, boolean done) {}

    public static final class Reminders {
        private final Map<String, Reminder> store = new LinkedHashMap<>();
        private final AtomicInteger seq = new AtomicInteger(1);

        @Tool(name = "create_reminder", description = "Add a new reminder to the list")
        public Reminder create(
                @ToolParam(description = "What to remember") String title,
                @ToolParam(description = "One of: low, med, high", required = false) String priority) {
            String id = "r-" + seq.getAndIncrement();
            Reminder r = new Reminder(id, title, priority == null ? "med" : priority, Instant.now(), false);
            store.put(id, r);
            return r;
        }

        @Tool(name = "list_reminders", description = "List reminders, optionally filtered by a title substring")
        public List<Reminder> list(
                @ToolParam(description = "Substring to match in the title; omit to list everything", required = false) String query) {
            List<Reminder> out = new ArrayList<>();
            String needle = query == null ? null : query.toLowerCase();
            for (Reminder r : store.values()) {
                if (needle != null && !r.title().toLowerCase().contains(needle)) continue;
                out.add(r);
            }
            return out;
        }

        @Tool(name = "complete_reminder", description = "Mark a reminder as completed by id")
        public Reminder complete(
                @ToolParam(description = "The reminder id returned by create_reminder") String id) {
            Reminder r = store.get(id);
            if (r == null) throw new IllegalArgumentException("Unknown reminder: " + id);
            Reminder done = new Reminder(r.id(), r.title(), r.priority(), r.createdAt(), true);
            store.put(id, done);
            return done;
        }
    }

    public static void main(String[] args) {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null) {
            System.err.println("Set ANTHROPIC_API_KEY to run this sample.");
            return;
        }

        try (var kite = Kite.builder()
                .provider(new AnthropicProvider(key))
                .build()) {

            var agent = Agent.builder()
                    .model("claude-sonnet-4-6")
                    .name("reminders")
                    .instructions("You manage the user's reminders. Always use the tools; don't "
                            + "answer from memory. Only pass optional arguments when the user gave you a value for them.")
                    .tools(new Reminders())
                    .build();

            System.out.println("Tools discovered on bean:");
            for (var t : agent.tools()) {
                System.out.println("  " + t.name() + "  " + t.paramsSchema().writeJson());
            }
            System.out.println();

            var reply = kite.run(agent,
                    "Add a reminder to call Alex tomorrow — high priority. "
                            + "Also add 'buy milk'. Then list everything I have.");
            System.out.println(reply.text());
        }
    }
}
