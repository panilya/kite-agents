package io.kite.samples.research.tools;

import java.util.List;

/**
 * Pluggable search backend. The sample ships two implementations: {@link TavilySearchProvider}
 * for live runs and {@link MockSearchProvider} for offline runs (activated when
 * {@code TAVILY_API_KEY} is unset, so the sample always executes end-to-end).
 */
public interface SearchProvider {
    List<SearchHit> search(String query, int maxResults);
}
