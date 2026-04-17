package io.kite.samples.research.tools;

/**
 * Offline fetcher. Returns synthetic, topic-shaped prose for mock URLs produced by
 * {@link MockSearchProvider}, and an explicit offline-mode error for everything else so the
 * LLM can degrade gracefully.
 */
public final class MockFetchProvider implements FetchProvider {

    @Override
    public String fetch(String url, int maxChars) {
        if (url == null || url.isBlank()) return "ERROR: empty URL";
        if (!url.startsWith("https://example.com/research/")) {
            return "ERROR: offline mode — cannot fetch " + url + ". Rely on the search snippets instead.";
        }

        String slug = url.substring("https://example.com/research/".length());
        String topic = slug.replaceAll("-(overview|tradeoffs|case-study|benchmarks|industry-report)-\\d+$", "")
                .replace('-', ' ');
        String angle = slug.replaceAll(".*-(overview|tradeoffs|case-study|benchmarks|industry-report)-\\d+$", "$1")
                .replace('-', ' ');

        String body = "Synthetic article on " + topic + ". "
                + "Section 1: background. The field of " + topic
                + " has evolved rapidly over the last decade, driven by research and industry adoption. "
                + "Section 2: " + angle + ". Recent work focuses on " + angle
                + " and its implications for practitioners. Representative studies report meaningful"
                + " improvements when applying established techniques, though measurement methodology"
                + " varies across reports. "
                + "Section 3: open questions. Despite progress, the literature notes open questions in"
                + " evaluation, reproducibility, and long-term maintenance costs. "
                + "Section 4: takeaways. Readers deciding whether to adopt " + topic
                + " should weigh the reported benefits against integration cost and operational overhead.";
        if (body.length() > maxChars) body = body.substring(0, maxChars) + "…";
        return body;
    }
}
