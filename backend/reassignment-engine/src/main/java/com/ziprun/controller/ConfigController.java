package com.ziprun.controller;

import com.ziprun.routing.RoutingService;
import com.ziprun.routing.Zones;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GET /config - the settings the console needs to render forms and load bars:
 * zones (with neighbours), the fleet's default capacity and the deadline rules.
 */
@RestController
public class ConfigController {

    private final RoutingService routingService;
    private final int defaultSlaMinutes;
    private final int slaAtRiskMinutes;
    private final boolean demoTools;

    public ConfigController(RoutingService routingService,
                            @Value("${orders.default-sla-minutes:120}") int defaultSlaMinutes,
                            @Value("${orders.sla.at-risk-minutes:30}") int slaAtRiskMinutes,
                            @Value("${app.demo-tools:false}") boolean demoTools) {
        this.routingService = routingService;
        this.defaultSlaMinutes = defaultSlaMinutes;
        this.slaAtRiskMinutes = slaAtRiskMinutes;
        this.demoTools = demoTools;
    }

    @GetMapping("/config")
    public AppConfig config() {
        return new AppConfig(routingService.getDefaultCapacity(), defaultSlaMinutes, slaAtRiskMinutes, Zones.all(), demoTools);
    }

    /** defaultMaxCapacity 0 = no limit. demoTools: show the phone-app simulator on the Fleet page. */
    public record AppConfig(int defaultMaxCapacity, int defaultSlaMinutes, int slaAtRiskMinutes, List<Zones.Zone> zones,
                            boolean demoTools) {
    }
}
