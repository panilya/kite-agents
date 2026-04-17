package io.kite.samples.research;

import io.kite.annotations.Description;

import java.util.List;

/**
 * Structured output of the searcher sub-agent. One subtopic's worth of investigation: a list of
 * claims with the sources that back them. The coordinator receives this JSON as a tool result
 * and uses it (plus other subtopics' findings) to compose the final report.
 */
public record Findings(
        @Description("Restatement of the subtopic investigated") String subtopic,
        @Description("Concrete claims extracted from the sources; each claim must cite at least one source by index") List<Claim> claims,
        @Description("Sources consulted, in the order they appear in the claims' sourceIndices") List<Source> sources) {

    public record Claim(
            @Description("One factual claim, self-contained, without meta commentary") String text,
            @Description("Zero-based indices into the sources array that support this claim") List<Integer> sourceIndices) {}

    public record Source(
            @Description("Absolute URL of the source") String url,
            @Description("Page or article title") String title,
            @Description("Short snippet or summary justifying inclusion (1–2 sentences)") String snippet) {}
}
