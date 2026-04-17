package io.kite.samples.research.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic in-memory search backend for offline sample runs. Results are synthetic but
 * plausibly shaped so the downstream LLM can still exercise the claim-extraction and citation
 * path. URLs begin with {@code https://example.com/research/} so {@link MockFetchProvider} can
 * recognize and serve canned bodies for them.
 */
public final class MockSearchProvider implements SearchProvider {

    @Override
    public List<SearchHit> search(String query, int maxResults) {
        String q = query == null ? "" : query.trim();
        int n = Math.max(1, Math.min(maxResults, 5));
        List<SearchHit> out = new ArrayList<>(n);
        String[] angles = {"overview", "tradeoffs", "case-study", "benchmarks", "industry-report"};
        for (int i = 0; i < n; i++) {
            String angle = angles[i % angles.length];
            String slug = slugify(q) + "-" + angle + "-" + (i + 1);
            out.add(new SearchHit(
                    toTitle(q) + " — " + humanize(angle) + " (result " + (i + 1) + ")",
                    "https://example.com/research/" + slug,
                    "Synthetic result " + (i + 1) + " about " + q
                            + ". Focuses on " + angle.replace('-', ' ')
                            + "; provides background and representative numbers."));
        }
        return out;
    }

    private static String slugify(String s) {
        String t = s.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        if (t.startsWith("-")) t = t.substring(1);
        if (t.endsWith("-")) t = t.substring(0, t.length() - 1);
        if (t.isEmpty()) t = "topic";
        return t.length() > 48 ? t.substring(0, 48) : t;
    }

    private static String toTitle(String q) {
        if (q.isEmpty()) return "Topic";
        return Character.toUpperCase(q.charAt(0)) + q.substring(1);
    }

    private static String humanize(String kebab) {
        String[] parts = kebab.split("-");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            String p = parts[i];
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
