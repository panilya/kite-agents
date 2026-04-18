package io.kite;

import io.kite.internal.runtime.Runner;
import io.kite.model.ModelProvider;
import io.kite.tracing.TracingProvider;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * The runtime. Configured once at startup, shared across the application, holds model provider
 * connections, conversation storage, tracing pipelines, and execution defaults. Think of it like
 * a {@code DataSource}: infrastructure, not per-request state.
 *
 * <p>Multiple {@link Kite} instances can coexist (for example production vs test).
 */
public final class Kite implements AutoCloseable {

    private final List<ModelProvider> providers;
    private final ExecutorService vexec;
    private final Runner runner;
    private final TracingProvider tracer;

    Kite(List<ModelProvider> providers, ExecutorService vexec, Runner runner, TracingProvider tracer) {
        this.providers = providers;
        this.vexec = vexec;
        this.runner = runner;
        this.tracer = tracer;
    }

    public static KiteBuilder builder() {
        return new KiteBuilder();
    }

    public List<ModelProvider> providers() {
        return providers;
    }

    /* ============================ Non-streaming entry points ============================ */

    public Reply run(Agent<Void> agent, String input) {
        return runner.run(agent, input, null, null);
    }

    public Reply run(Agent<Void> agent, String input, String conversationId) {
        return runner.run(agent, input, conversationId, null);
    }

    public <T> Reply run(Agent<T> agent, String input, T context) {
        return runner.run(agent, input, null, context);
    }

    public <T> Reply run(Agent<T> agent, String input, String conversationId, T context) {
        return runner.run(agent, input, conversationId, context);
    }

    /* ============================== Streaming entry points ============================== */

    public void stream(Agent<Void> agent, String input, Consumer<Event> onEvent) {
        runner.stream(agent, input, null, null, onEvent);
    }

    public void stream(Agent<Void> agent, String input, String conversationId, Consumer<Event> onEvent) {
        runner.stream(agent, input, conversationId, null, onEvent);
    }

    public <T> void stream(Agent<T> agent, String input, T context, Consumer<Event> onEvent) {
        runner.stream(agent, input, null, context, onEvent);
    }

    public <T> void stream(Agent<T> agent, String input, String conversationId, T context, Consumer<Event> onEvent) {
        runner.stream(agent, input, conversationId, context, onEvent);
    }

    @Override
    public void close() {
        try {
            tracer.close();
        } catch (Exception e) {
            System.err.println("[kite] tracer " + tracer.getClass().getName()
                    + " failed to close: " + e.getMessage());
        }
        vexec.shutdown();
    }
}
