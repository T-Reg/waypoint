package dev.waypoint.visualizer.scenario;

import java.util.Map;

/** Progress of one run, published when it starts and when it finishes. */
public record RunUpdate(
        String runId,
        String scenarioId,
        String title,
        String expect,
        Phase phase,
        int total,
        int completed,
        Map<String, Long> byStatus,
        Map<String, Long> byServedBy,
        long durationMs) {

    public enum Phase { STARTED, FINISHED }
}
