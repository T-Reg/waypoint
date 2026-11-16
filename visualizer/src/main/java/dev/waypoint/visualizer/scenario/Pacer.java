package dev.waypoint.visualizer.scenario;

import java.time.Duration;

/** Waits between request starts; a bean so tests can replace it with a no-op. */
@FunctionalInterface
public interface Pacer {

    void pause(Duration interval) throws InterruptedException;

    static Pacer sleeping() {
        return interval -> {
            if (!interval.isZero()) {
                Thread.sleep(interval);
            }
        };
    }
}
