package com.gradesystem.service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A "photo" of the statistics at one moment in time.
 * The dashboard thread creates a new snapshot every 5 seconds; the menu just shows the latest one.
 * Because a snapshot never changes after it is created, it is safe to share between threads.
 */
public record StatsSnapshot(
        int studentCount,
        int courseCount,
        double averageScore,
        double averageGpa,
        double highestAverage,
        double lowestAverage,
        Map<String, Long> letterDistribution,
        LocalDateTime createdAt) {
}
