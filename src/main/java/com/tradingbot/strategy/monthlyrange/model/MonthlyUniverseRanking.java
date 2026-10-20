package com.tradingbot.strategy.monthlyrange.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Top candidates ranked across the monthly universe: Top 3 for directional broken-wing butterflies
 * and Top 3 for delta-neutral sideways strangles/condors.
 *
 * @param topDirectionalPicks up to 3 highest-conviction directional scores (conviction >=
 *     threshold)
 * @param topSidewaysPicks up to 3 most balanced/range-bound candidates
 */
public record MonthlyUniverseRanking(
        List<MonthlyDirectionalScore> topDirectionalPicks,
        List<MonthlyDirectionalScore> topSidewaysPicks) {

    public MonthlyUniverseRanking {
        topDirectionalPicks =
                topDirectionalPicks != null ? List.copyOf(topDirectionalPicks) : List.of();
        topSidewaysPicks = topSidewaysPicks != null ? List.copyOf(topSidewaysPicks) : List.of();
    }

    /**
     * Extracts and ranks Top 3 Directional (dominant probability >= strongThreshold, sorted
     * descending) and Top 3 Sideways (non-directional candidates closest to 50%).
     *
     * @param scores all evaluated directional scores
     * @param strongThreshold threshold for strong conviction (e.g. 65.0)
     * @return ranking summary
     */
    public static MonthlyUniverseRanking fromScores(
            List<MonthlyDirectionalScore> scores, double strongThreshold) {
        if (scores == null || scores.isEmpty()) {
            return new MonthlyUniverseRanking(List.of(), List.of());
        }

        // Directional: Filter for conviction >= strongThreshold, sort by highest deviation
        // descending
        List<MonthlyDirectionalScore> directional =
                scores.stream()
                        .filter(Objects::nonNull)
                        .filter(
                                s ->
                                        Math.max(
                                                        s.bullishProbabilityPct(),
                                                        100.0 - s.bullishProbabilityPct())
                                                >= strongThreshold)
                        .sorted(
                                Comparator.comparingDouble(
                                                (MonthlyDirectionalScore s) ->
                                                        Math.abs(s.bullishProbabilityPct() - 50.0))
                                        .reversed())
                        .limit(3)
                        .toList();

        // Sideways: Filter for candidates below strongThreshold, sort by lowest deviation from 50%
        // ascending
        List<MonthlyDirectionalScore> sideways =
                scores.stream()
                        .filter(Objects::nonNull)
                        .filter(
                                s ->
                                        Math.max(
                                                        s.bullishProbabilityPct(),
                                                        100.0 - s.bullishProbabilityPct())
                                                < strongThreshold)
                        .sorted(
                                Comparator.comparingDouble(
                                        (MonthlyDirectionalScore s) ->
                                                Math.abs(s.bullishProbabilityPct() - 50.0)))
                        .limit(3)
                        .toList();

        return new MonthlyUniverseRanking(directional, sideways);
    }
}
