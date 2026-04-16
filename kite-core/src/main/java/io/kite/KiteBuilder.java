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
    private final List<Guard<?>> globalBefore = new ArrayList<>();
    private final List<Guard<?>> globalAfter = new ArrayList<>();
    private String defaultModel;
    private int defaultMaxTurns = 25;
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

    public KiteBuilder globalBefore(Guard<?> guard) {
        globalBefore.add(Objects.requireNonNull(guard, "guard"));
        return this;
    }

    public KiteBuilder globalAfter(Guard<?> guard) {
        globalAfter.add(Objects.requireNonNull(guard, "guard"));
        return this;
    }

    public KiteBuilder defaultModel(String model) {
        this.defaultModel = model;
        return this;
    }

    public KiteBuilder defaultMaxTurns(int n) {
        if (n < 1) throw new IllegalArgumentException("defaultMaxTurns must be >= 1");
        this.defaultMaxTurns = n;
        return this;
    }

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
                List.copyOf(providers), store, effectiveTracer, guardExec, defaultMaxTurns, toolTimeout, vexec);
        var runner = new Runner(core, List.copyOf(globalBefore), List.copyOf(globalAfter));
        return new Kite(List.copyOf(providers), vexec, runner);
    }

    private static TracingProvider defaultTracer() {
        // Console tracing on by default in development; off if KITE_TRACING=off.
        String env = System.getenv("KITE_TRACING");
        if ("off".equalsIgnoreCase(env)) return NoOpTracingProvider.INSTANCE;
        return new ConsoleTracingProvider(false);
    }
}
