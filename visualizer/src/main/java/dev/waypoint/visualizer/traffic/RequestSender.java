package dev.waypoint.visualizer.traffic;

/** Sends one request through the gateway stack; the seam between scenario runs and HTTP. */
public interface RequestSender {

    RequestResult send(RequestSpec spec, String runId, String scenarioId);

    /** Throws {@link IllegalArgumentException} if the target is not configured. */
    void checkTarget(String target);
}
