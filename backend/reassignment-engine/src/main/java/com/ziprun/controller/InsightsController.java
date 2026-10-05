package com.ziprun.controller;

import com.ziprun.domain.Activity;
import com.ziprun.service.activity.ActivityService;
import com.ziprun.service.activity.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * History and metrics for the Insights page.
 *
 * GET /activity?limit=50 - newest first: status changes, suggestions, decisions, reassignments
 * GET /metrics           - suggestion outcomes per source (AI / rule-based / fallback), timings
 */
@RestController
public class InsightsController {

    private final ActivityService activityService;
    private final MetricsService metricsService;

    public InsightsController(ActivityService activityService, MetricsService metricsService) {
        this.activityService = activityService;
        this.metricsService = metricsService;
    }

    @GetMapping("/activity")
    public List<Activity> activity(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return activityService.recent(limit);
    }

    @GetMapping("/metrics")
    public MetricsService.Summary metrics() {
        return metricsService.summary();
    }
}
