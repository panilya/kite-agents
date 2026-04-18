package io.kite.samples.research;

import io.kite.Agent;
import io.kite.Event;
import io.kite.Kite;
import io.kite.anthropic.AnthropicProvider;
import io.kite.samples.research.guards.CitationGuard;
import io.kite.samples.research.guards.TopicSanityGuard;
import io.kite.samples.research.tools.FetchProvider;
import io.kite.samples.research.tools.FetchUrlTool;
import io.kite.samples.research.tools.MockFetchProvider;
import io.kite.samples.research.tools.MockSearchProvider;
import io.kite.samples.research.tools.RealFetchProvider;
import io.kite.samples.research.tools.SearchProvider;
import io.kite.samples.research.tools.TavilySearchProvider;
import io.kite.samples.research.tools.WebSearchTool;

import java.time.Duration;
import java.util.List;

/**
 * Deep research agent — a multi-agent orchestrator-worker pipeline built entirely from Kite
 * primitives.
 *
 * <pre>
 *   user query
 *       │
 *       ▼
 *   Coordinator (claude-opus-4-7)
 *     │  inputGuards:  [topic-sanity]
 *     │  outputGuards: [citation-presence]
 *     │  parallelToolCalls = true
 *     │
 *     ├─► plan_research        ─► Planner sub-agent     (structured output: Plan)
 *     └─► investigate_topic ×N ─► Searcher sub-agent    (tools: web_search, fetch_url;
 *                                                       structured output: Findings)
 * </pre>
 *
 * <p>Demonstrates: agent-as-tool delegation, typed context with {@code @Ctx}-driven tool and
 * instruction behavior, structured record outputs, parallel tool calls, input + output guards,
 * read-only tools, and live streaming events.
 *
 * <p>Run:
 * <pre>
 *   export ANTHROPIC_API_KEY=...
 *   export TAVILY_API_KEY=...             # optional — unset to run in offline mock mode
 *   ./gradlew :kite-samples:runSample \
 *       -Psample=io.kite.samples.research.DeepResearchAgent \
 *       --args="What are the tradeoffs between orchestrator-worker and handoff agent patterns?"
 * </pre>
 */
public final class DeepResearchAgent {

    // Default both to Sonnet — keeps the sample cheap and fast. Set DEEP_RESEARCH_LEAD_MODEL=claude-opus-4-7
    // to reproduce Anthropic's published Opus-lead / Sonnet-worker blueprint (≈5× the cost).
    private static final String MODEL_LEAD =
            System.getenv().getOrDefault("DEEP_RESEARCH_LEAD_MODEL", "claude-sonnet-4-6");
    private static final String MODEL_WORKER = "claude-sonnet-4-6";

    public static void main(String[] args) {
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        if (anthropicKey == null) {
            System.err.println("Set ANTHROPIC_API_KEY to run this sample.");
            return;
        }
        String tavilyKey = System.getenv("TAVILY_API_KEY");
        boolean offline = (tavilyKey == null || tavilyKey.isBlank());
        System.out.println("Mode: " + (offline ? "offline (MockSearchProvider)" : "live (Tavily)"));

        SearchProvider search = offline ? new MockSearchProvider() : new TavilySearchProvider(tavilyKey);
        FetchProvider fetch = offline ? new MockFetchProvider() : new RealFetchProvider();

        String query = args.length > 0
                ? String.join(" ", args)
                : "What are the tradeoffs between orchestrator-worker and handoff agent patterns?";

        try (var kite = Kite.builder()
                .provider(new AnthropicProvider(anthropicKey))
                .toolTimeout(Duration.ofSeconds(20))
                .build()) {

            Agent<ResearchContext> planner = buildPlanner();
            Agent<ResearchContext> searcher = buildSearcher(search, fetch);
            Agent<ResearchContext> coordinator = buildCoordinator(planner, searcher);

            var ctx = ResearchContext.defaults("demo-user");

            System.out.println();
            System.out.println("> " + query);
            System.out.println();
            var reply = streamWithProgress(kite, coordinator, query, ctx);
            printOutcome(reply);
        }
    }

    // ---------- agent builders ----------

    private static Agent<ResearchContext> buildPlanner() {
        return Agent.builder(ResearchContext.class)
                .model(MODEL_WORKER)
                .name("planner")
                .description("Decomposes the research question into focused subtopics.")
                .instructions(ctx -> """
                        Decompose the user's research question into %d focused subtopics (no more).
                        Each subtopic must have:
                          * a short title (3-7 words)
                          * a one-sentence objective stating what "done" looks like
                          * a concrete starter search query

                        Subtopics should be orthogonal — avoid overlap that would duplicate work
                        across parallel searchers.
                        """.formatted(Math.max(3, Math.min(ctx.maxSubtopics(), 5))))
                .output(Plan.class)
                .maxTurns(2)
                .build();
    }

    private static Agent<ResearchContext> buildSearcher(SearchProvider search, FetchProvider fetch) {
        return Agent.builder(ResearchContext.class)
                .model(MODEL_WORKER)
                .name("searcher")
                .description("Investigates one subtopic with web_search and fetch_url; returns findings.")
                .instructions("""
                        Investigate the subtopic the coordinator assigned you.

                        Workflow:
                          1. Issue 1-3 web_search queries — parallel when topics are independent.
                          2. Fetch 2-4 of the most relevant URLs with fetch_url for full content.
                          3. Extract concrete claims. Every claim MUST cite at least one source
                             by its index in your sources[] array.
                          4. Return a Findings record with subtopic, claims, and sources.

                        Do not speculate — if the sources don't support a claim, drop it. Prefer
                        primary sources, recent material, and fewer high-quality claims over many
                        weak ones. Stop after one round of synthesis; do not loop indefinitely.
                        """)
                .tool(WebSearchTool.create(search))
                .tool(FetchUrlTool.create(fetch))
                .output(Findings.class)
                .parallelToolCalls(true)
                .maxTurns(4)
                .build();
    }

    private static Agent<ResearchContext> buildCoordinator(
            Agent<ResearchContext> planner,
            Agent<ResearchContext> searcher) {

        return Agent.builder(ResearchContext.class)
                .model(MODEL_LEAD)
                .name("coordinator")
                .description("Lead researcher. Plans, dispatches parallel searchers, writes the final report.")
                .instructions(ctx -> """
                        You are a lead researcher orchestrating a multi-agent investigation for
                        user %s. You have two delegate tools and must follow this workflow:

                        STEP 1 — plan_research(input=<the user's question>)
                          * Call exactly once.

                        STEP 2 — investigate_topic(input=<one subtopic description>)
                          * Call this tool ONCE PER SUBTOPIC from the plan.
                          * Issue all of these calls in a SINGLE assistant turn so they execute
                            in parallel — do not wait for one to finish before launching the next.
                          * Pass a self-contained prompt describing the subtopic's title, objective,
                            and starter query so the searcher needs no additional context.
                          * DO NOT RETRY FAILED TOOL CALLS. If a searcher returns an error
                            (rate limit, timeout, provider failure), accept the failure, skip
                            that subtopic, and proceed to step 3 with the findings you have.
                            Retrying rate-limit errors will never succeed within this run.

                        STEP 3 — compose the final report.
                          * Output a markdown document with this shape:

                              # <Report title>

                              <2-3 sentence executive summary>

                              ## <Section 1 heading>
                              <Prose with inline citations like [1], [2]>

                              ## <Section 2 heading>
                              ...

                              ## Sources
                              [1] <url>
                              [2] <url>
                              ...

                          * Every non-trivial claim must carry a [N] citation.
                          * Number sources globally across all subtopics — deduplicate URLs.
                          * Aim for 3-5 sections, each 1-2 paragraphs. Prefer depth over breadth.

                        Budget: roughly %s wall-clock across all tool calls. Do not re-plan or
                        re-investigate past step 2; go to step 3 with whatever findings you have.
                        """.formatted(ctx.userId(), ctx.totalBudget().toSeconds() + "s"))
                .tool(planner.asTool("plan_research",
                        "Ask the planner to decompose the question into 3-5 parallel subtopics."))
                .tool(searcher.asTool("investigate_topic",
                        "Dispatch one searcher to investigate a single subtopic and return findings."))
                .inputGuards(List.of(TopicSanityGuard.<ResearchContext>create()))
                .outputGuards(List.of(CitationGuard.<ResearchContext>create()))
                .parallelToolCalls(true)
                .maxTurns(12)
                .build();
    }

    // ---------- streaming UX ----------

    private static io.kite.Reply streamWithProgress(
            Kite kite, Agent<ResearchContext> coordinator, String input, ResearchContext ctx) {

        ProgressPrinter progress = new ProgressPrinter();
        final io.kite.Reply[] finalReply = {null};
        kite.stream(coordinator, input, ctx, event -> {
            switch (event) {
                case Event.ToolCall tc -> progress.onToolCall(tc.name(), tc.argsJson());
                case Event.ToolResult tr -> progress.onToolResult(tr.name(), tr.elapsed());
                case Event.GuardCheck gc -> {
                    if (gc.outcome().blocked()) {
                        System.out.println("[blocked by " + gc.outcome().name() + "] " + gc.outcome().message());
                    }
                }
                case Event.Done d -> finalReply[0] = d.reply();
                case Event.Error e -> System.err.println("[error] " + e.cause().getMessage());
                default -> { /* ignore Delta + Transfer for this progress view */ }
            }
        });
        return finalReply[0];
    }

    private static void printOutcome(io.kite.Reply reply) {
        if (reply == null) {
            System.err.println("(no reply — check errors above)");
            return;
        }
        System.out.println();
        switch (reply.status()) {
            case BLOCKED -> System.out.println("BLOCKED: " + reply.blockReason());
            case MAX_TURNS -> {
                System.out.println("MAX_TURNS reached. Partial result:");
                System.out.println(reply.text());
            }
            case OK -> {
                System.out.println("===== REPORT =====");
                System.out.println(reply.text());
                System.out.println("===== END REPORT =====");
                System.out.println("(" + reply.usage().totalTokens() + " tokens)");
            }
        }
    }

    /** Simple progress printer: one line per tool call with timing. */
    private static final class ProgressPrinter {
        private int investigateCount = 0;

        void onToolCall(String tool, String argsJson) {
            String label = switch (tool) {
                case "plan_research" -> "[plan]";
                case "investigate_topic" -> {
                    investigateCount++;
                    yield "[search " + investigateCount + "]";
                }
                case "web_search" -> "  [web_search]";
                case "fetch_url" -> "  [fetch_url]";
                default -> "[" + tool + "]";
            };
            System.out.println(label + " " + firstLine(argsJson, 120));
        }

        void onToolResult(String tool, Duration elapsed) {
            if (tool.equals("investigate_topic") || tool.equals("plan_research")) {
                System.out.println("           done in " + elapsed.toMillis() + "ms");
            }
        }

        private static String firstLine(String s, int max) {
            if (s == null) return "";
            int nl = s.indexOf('\n');
            String one = nl < 0 ? s : s.substring(0, nl);
            return one.length() <= max ? one : one.substring(0, max) + "…";
        }
    }
}
