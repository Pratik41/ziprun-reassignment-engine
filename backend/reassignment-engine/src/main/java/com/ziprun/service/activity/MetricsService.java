package com.ziprun.service.activity;

import com.ziprun.domain.SuggestionStatus;
import com.ziprun.repository.OrderRepository;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Is the AI actually better than rule-based?" Suggestion outcomes grouped by
 * what produced them (AI, rule-based, rule-based standing in for a failed AI),
 * plus how often ops follows the recommended agent when creating an order.
 *
 * Acceptance rate = accepted / (accepted + rejected). EXPIRED suggestions were
 * withdrawn by the system, not judged by a person, so they don't count either way.
 *
 * The database does the counting (GROUP BY source, status), so this costs the same
 * with 100 suggestions or a million. Only the 95th-percentile response time needs
 * individual values; it is taken over the latest P95_SAMPLE timed suggestions.
 */
@Service
public class MetricsService {

    static final int P95_SAMPLE = 2000;

    public record SourceStats(String key, String label, long total, long accepted, long rejected, long expired,
                              long pending, Double acceptanceRate, Double avgConfidence,
                              Long avgRoutingMs, Long p95RoutingMs) {
    }

    /** New orders created with a recommendation on screen, and how many went to the recommended agent. */
    public record NewOrderPicks(long recommended, long followed, Double followRate) {
    }

    public record Summary(long totalSuggestions, long decided, Double acceptanceRate, Double aiFallbackRate,
                          Map<String, Long> aiProviders, List<SourceStats> bySource, NewOrderPicks newOrderPicks) {
    }

    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("ai", "AI");
        LABELS.put("rule-based", "Rule-based");
        LABELS.put("fallback", "AI fallback (rule-based)");
        LABELS.put("unknown", "Before tracking");
    }

    /** Running totals for one kind of source while folding the grouped rows. */
    private static final class Tally {
        long total, accepted, rejected, expired, pending, timedCount;
        double confidenceSum, routingSum;
        final List<Long> recentTimes = new ArrayList<>();
    }

    private final ReassignmentSuggestionRepository suggestions;
    private final OrderRepository orders;

    public MetricsService(ReassignmentSuggestionRepository suggestions, OrderRepository orders) {
        this.suggestions = suggestions;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        Map<String, Tally> byKind = new LinkedHashMap<>();
        LABELS.keySet().forEach(k -> byKind.put(k, new Tally()));
        Map<String, Long> providers = new TreeMap<>();

        // [source, status, count, sum(confidence), count(routingMillis), sum(routingMillis)]
        for (Object[] row : suggestions.statsBySourceAndStatus()) {
            String source = (String) row[0];
            SuggestionStatus status = (SuggestionStatus) row[1];
            long count = ((Number) row[2]).longValue();
            String kind = kind(source);
            Tally t = byKind.get(kind);
            t.total += count;
            t.confidenceSum += row[3] == null ? 0 : ((Number) row[3]).doubleValue();
            t.timedCount += ((Number) row[4]).longValue();
            t.routingSum += row[5] == null ? 0 : ((Number) row[5]).doubleValue();
            switch (status) {
                case ACCEPTED -> t.accepted += count;
                case REJECTED -> t.rejected += count;
                case EXPIRED -> t.expired += count;
                case PENDING -> t.pending += count;
            }
            if (kind.equals("ai")) {
                providers.merge(source.substring(3), count, Long::sum);
            }
        }
        // [source, routingMillis], newest first
        for (Object[] row : suggestions.recentRoutingTimes(PageRequest.of(0, P95_SAMPLE))) {
            byKind.get(kind((String) row[0])).recentTimes.add(((Number) row[1]).longValue());
        }

        List<SourceStats> stats = new ArrayList<>();
        byKind.forEach((kind, t) -> {
            if (t.total > 0 || !kind.equals("unknown")) {
                stats.add(stats(kind, t));
            }
        });

        long total = byKind.values().stream().mapToLong(t -> t.total).sum();
        long accepted = byKind.values().stream().mapToLong(t -> t.accepted).sum();
        long rejected = byKind.values().stream().mapToLong(t -> t.rejected).sum();
        long ai = byKind.get("ai").total;
        long fallback = byKind.get("fallback").total;
        return new Summary(total, accepted + rejected, ratio(accepted, accepted + rejected),
            ratio(fallback, ai + fallback), providers, stats, newOrderPicks());
    }

    private NewOrderPicks newOrderPicks() {
        long recommended = orders.countByFollowedRecommendationIsNotNull();
        long followed = orders.countByFollowedRecommendation(true);
        return new NewOrderPicks(recommended, followed, ratio(followed, recommended));
    }

    static String kind(String source) {
        if (source == null) return "unknown";
        if (source.startsWith("ai:")) return "ai";
        if (source.contains("fallback")) return "fallback";
        if (source.equals("rule-based")) return "rule-based";
        return "unknown";
    }

    private static SourceStats stats(String kind, Tally t) {
        List<Long> times = t.recentTimes.stream().sorted().toList();
        return new SourceStats(kind, LABELS.get(kind), t.total, t.accepted, t.rejected, t.expired, t.pending,
            ratio(t.accepted, t.accepted + t.rejected),
            t.total == 0 ? null : t.confidenceSum / t.total,
            t.timedCount == 0 ? null : Math.round(t.routingSum / t.timedCount),
            times.isEmpty() ? null : times.get(Math.max(0, (int) Math.ceil(times.size() * 0.95) - 1)));
    }

    private static Double ratio(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }
}
