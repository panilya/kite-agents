package io.kite;

import io.kite.internal.runtime.GuardExecutor;
import io.kite.internal.runtime.InMemoryConversationStore;
import io.kite.internal.runtime.Runner;
import io.kite.internal.runtime.RunnerCore;
import io.kite.model.ModelProvider;
import io.kite.tracing.ConsoleTracingProvider;
import io.kite.tracing.NoOpTracingProvider;
import io.kite.tracing.TracingProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class KiteBuilder {

    private final List<ModelProvider> providers = new ArrayList<>();
    private ConversationStore conversationStore;
    private TracingProvider tracer;
    private int maxTurns = 25;
    private Duration toolTimeout = Duration.ofSeconds(30);

    KiteBuilder() {}

    public KiteBuilder provider(ModelProvider provider) {
        providers.add(Objects.requireNonNull(provider, "provider"));
        return this;
    }

    public KiteBuilder conversationStore(ConversationStore store) {
        this.conversationStore = store;
        return this;
    }

    public KiteBuilder tracing(TracingProvider provider) {
        this.tracer = provider;
        return this;
    }

    /** Fallback cap on tool-loop iterations when an agent does not set its own {@link AgentBuilder#maxTurns(int)}. */
    public KiteBuilder maxTurns(int n) {
        if (n < 1) throw new IllegalArgumentException("maxTurns must be >= 1");
        this.maxTurns = n;
        return this;
    }

    /** Per-tool-invocation deadline. Defaults to 30 seconds. */
    public KiteBuilder toolTimeout(Duration d) {
        this.toolTimeout = Objects.requireNonNull(d, "toolTimeout");
        return this;
    }

    public Kite build() {
        if (providers.isEmpty()) {
            throw new IllegalStateException("Kite.builder() requires at least one ModelProvider");
        }
        ConversationStore store = conversationStore != null ? conversationStore : new InMemoryConversationStore();
        TracingProvider effectiveTracer = tracer != null ? tracer : defaultTracer();
        ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor();
        var guardExec = new GuardExecutor(vexec);
        var core = new RunnerCore(
                List.copyOf(providers), store, effectiveTracer, guardExec, maxTurns, toolTimeout, vexec);
        var runner = new Runner(core);
        return new Kite(List.copyOf(providers), vexec, runner, effectiveTracer);
    }

    private static TracingProvider defaultTracer() {
        String env = System.getenv("KITE_TRACING");
        if ("off".equalsIgnoreCase(env)) return NoOpTracingProvider.INSTANCE;
        return new ConsoleTracingProvider(false);
    }
}
