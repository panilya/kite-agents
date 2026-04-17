package io.kite.samples.research.guards;

import io.kite.Guard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Output guard: enforces that the final research report contains proper citations. The report
 * must:
 * <ul>
 *   <li>Contain a {@code ## Sources} section (or {@code Sources:}).</li>
 *   <li>Reference at least two distinct numbered citations like {@code [1]}, {@code [2]}.</li>
 * </ul>
 */
public final class CitationGuard {

    private static final Pattern SOURCES_SECTION = Pattern.compile(
            "(?im)^\\s*(##\\s*sources|sources\\s*:)\\b");
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d+)]");
    private static final int MIN_DISTINCT_CITATIONS = 2;

    private CitationGuard() {}

    public static <T> Guard<T> create() {
        return Guard.<T>outputTyped("citation-presence").check(output -> {
            if (output == null || output.isBlank()) {
                return Guard.block("Empty report — no content to cite.");
            }
            String text = output.trim();
            if (!SOURCES_SECTION.matcher(text).find()) {
                return Guard.block(
                        "Report has no `## Sources` section. Every deep-research report must list its sources.");
            }
            java.util.Set<Integer> distinct = new java.util.HashSet<>();
            Matcher m = CITATION_MARKER.matcher(text);
            while (m.find()) {
                try {
                    distinct.add(Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {
                    // matcher guarantees digits, ignore
                }
            }
            if (distinct.size() < MIN_DISTINCT_CITATIONS) {
                return Guard.block(
                        "Report has " + distinct.size() + " distinct citation marker(s); need at least "
                                + MIN_DISTINCT_CITATIONS + " to justify the claims.");
            }
            return Guard.pass();
        });
    }
}
