package io.kite.samples.research;

import io.kite.annotations.Description;

import java.util.List;

/**
 * Structured output of the planner sub-agent. Decomposes a research question into 3–5 focused
 * subtopics. Each subtopic carries a short objective and a starter search query so the searcher
 * can run without having to re-derive the plan.
 */
public record Plan(
        @Description("The restated top-level question this plan addresses") String question,
        @Description("Focused subtopics to investigate in parallel; 3 to 5 entries") List<Subtopic> subtopics) {

    public record Subtopic(
            @Description("Short title of the subtopic, 3–7 words") String title,
            @Description("One-sentence objective stating what 'done' looks like for this subtopic") String objective,
            @Description("A specific web search query that would kick off research on this subtopic") String starterQuery) {}
}
