package io.kite.samples.research.tools;

/** One result returned by a {@link SearchProvider}. */
public record SearchHit(String title, String url, String snippet) {}
