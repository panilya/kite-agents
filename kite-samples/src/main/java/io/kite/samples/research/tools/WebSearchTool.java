package io.kite.samples.research.tools;

import io.kite.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for the {@code web_search} tool. Declared {@code readOnly(true)} so Kite may start it
 * speculatively in parallel with any still-running input guards.
 */
public final class WebSearchTool {

    private WebSearchTool() {}

    public static Tool create(SearchProvider provider) {
        return Tool.create("web_search")
                .description("Search the web. Returns a list of hits with title, url, and snippet. "
                        + "Use short focused queries; issue multiple queries in parallel for breadth.")
                .param("query", String.class, "The search query. Keep it specific; one idea per query.")
                .param("maxResults", Integer.class, "Between 1 and 8; default 5 if unsure.", false, 5)
                .readOnly(true)
                .execute(args -> {
                    String query = (String) args.get("query");
                    int max = args.get("maxResults") instanceof Number n ? n.intValue() : 5;
                    List<SearchHit> hits = provider.search(query, max);
                    List<Map<String, Object>> rendered = new java.util.ArrayList<>(hits.size());
                    for (SearchHit h : hits) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("title", h.title());
                        m.put("url", h.url());
                        m.put("snippet", h.snippet());
                        rendered.add(m);
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("query", query);
                    result.put("resultCount", rendered.size());
                    result.put("results", rendered);
                    return result;
                })
                .build();
    }
}
