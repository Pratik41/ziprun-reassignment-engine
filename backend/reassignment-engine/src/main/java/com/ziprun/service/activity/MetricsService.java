package com.ziprun.service.activity;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * "Is the AI actually better than rule-based?" Suggestion outcomes grouped by
 * what produced them (AI, rule-based, rule-based standing in for a failed AI).
 *
 * Acceptance rate = accepted / (accepted + rejected). EXPIRED suggestions were
 * withdrawn by the system, not judged by a person, so they don't count either way.
 */
@Service
public class MetricsService {

    public record SourceStats(String key, String label, long total, long accepted, long rejected, long expired,
                              long pending, Double acceptanceRate, Double avgConfidence,
                              Long avgRoutingMs, Long p95RoutingMs) {
    }

    public record Summary(long totalSuggestions, long decided, Double acceptanceRate, Double aiFallbackRate,
                          Map<String, Long> aiProviders, List<SourceStats> bySource) {
    }

    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("ai", "AI");
        LABELS.put("rule-based", "Rule-based");
        LABELS.put("fallback", "AI fallback (rule-based)");
        LABELS.put("unknown", "Before tracking");
    }

    private final ReassignmentSuggestionRepository suggestions;

    public MetricsService(ReassignmentSuggestionRepository suggestions) {
        this.suggestions = suggestions;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        List<ReassignmentSuggestion> all = suggestions.findAll();

        Map<String, List<ReassignmentSuggestion>> byKind = new LinkedHashMap<>();
        LABELS.keySet().forEach(k -> byKind.put(k, new ArrayList<>()));
        Map<String, Long> providers = new TreeMap<>();
        for (ReassignmentSuggestion s : all) {
            String kind = kind(s.getSource());
            byKind.get(kind).add(s);
            if (kind.equals("ai")) {
                providers.merge(s.getSource().substring(3), 1L, Long::sum);
            }
        }

        List<SourceStats> stats = new ArrayList<>();
        byKind.forEach((kind, list) -> {
            if (!list.isEmpty() || !kind.equals("unknown")) {
                stats.add(stats(kind, list));
            }
        });

        long accepted = count(all, SuggestionStatus.ACCEPTED);
        long rejected = count(all, SuggestionStatus.REJECTED);
        long ai = byKind.get("ai").size();
        long fallback = byKind.get("fallback").size();
        return new Summary(all.size(), accepted + rejected, ratio(accepted, accepted + rejected),
            ratio(fallback, ai + fallback), providers, stats);
    }

    static String kind(String source) {
        if (source == null) return "unknown";
        if (source.startsWith("ai:")) return "ai";
        if (source.contains("fallback")) return "fallback";
        if (source.equals("rule-based")) return "rule-based";
        return "unknown";
    }

    private static SourceStats stats(String kind, List<ReassignmentSuggestion> list) {
        long accepted = count(list, SuggestionStatus.ACCEPTED);
        long rejected = count(list, SuggestionStatus.REJECTED);
        List<Long> times = list.stream().map(ReassignmentSuggestion::getRoutingMillis)
            .filter(Objects::nonNull).sorted().toList();
        Double avgConfidence = list.isEmpty() ? null
            : list.stream().mapToDouble(ReassignmentSuggestion::getConfidence).average().orElse(0);
        return new SourceStats(kind, LABELS.get(kind), list.size(), accepted, rejected,
            count(list, SuggestionStatus.EXPIRED), count(list, SuggestionStatus.PENDING),
            ratio(accepted, accepted + rejected), avgConfidence,
            times.isEmpty() ? null : Math.round(times.stream().mapToLong(Long::longValue).average().orElse(0)),
            times.isEmpty() ? null : times.get(Math.max(0, (int) Math.ceil(times.size() * 0.95) - 1)));
    }

    private static long count(List<ReassignmentSuggestion> list, SuggestionStatus status) {
        return list.stream().filter(s -> s.getStatus() == status).count();
    }

    private static Double ratio(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }
}
