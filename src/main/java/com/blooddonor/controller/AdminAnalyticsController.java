package com.blooddonor.controller;

import com.blooddonor.dto.AnalyticsDtos.Analytics;
import com.blooddonor.service.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN only (see SecurityConfig: /api/admin/**). */
@RestController
@RequestMapping("/api/admin")
public class AdminAnalyticsController {

    private final AnalyticsService analytics;

    public AdminAnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/analytics")
    public Analytics analytics() {
        return analytics.compute();
    }
}
