package dev.waypoint.visualizer.scenario;

import dev.waypoint.visualizer.traffic.RequestSpec;
import java.time.Duration;
import java.util.List;

/**
 * A one-click demo: one or more steps, each sending {@code count} copies of a request.
 * {@code parallel} runs all steps at once (e.g. hitting both gateways together); otherwise in order.
 */
public record Scenario(
        String id,
        String title,
        String category,
        String summary,
        String watch,
        String expect,
        boolean parallel,
        List<Step> steps) {

    public Scenario {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("scenario " + id + " has no steps");
        }
    }

    public int totalRequests() {
        return steps.stream().mapToInt(Step::count).sum();
    }

    /** {@code interval} spaces out request starts so the animation stays readable; zero means as fast as possible. */
    public record Step(String label, RequestSpec request, int count, int concurrency, Duration interval) {

        public Step {
            if (count < 1 || concurrency < 1 || interval.isNegative()) {
                throw new IllegalArgumentException("count and concurrency must be positive, interval non-negative");
            }
        }

        public static Step burst(String label, RequestSpec request, int count, int concurrency) {
            return new Step(label, request, count, concurrency, Duration.ZERO);
        }

        public static Step paced(String label, RequestSpec request, int count, long intervalMs) {
            return new Step(label, request, count, 1, Duration.ofMillis(intervalMs));
        }

        public static Step once(String label, RequestSpec request) {
            return paced(label, request, 1, 700);
        }
    }
}
