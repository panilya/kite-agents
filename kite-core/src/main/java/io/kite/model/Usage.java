package io.kite.model;

public record Usage(long promptTokens, long completionTokens, long totalTokens, double costUsd) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0.0);

    public Usage plus(Usage other) {
        if (other == null) return this;
        return new Usage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens,
                costUsd + other.costUsd);
    }
}
