package dev.waypoint.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.gateway.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class LocalRateLimiterProperties {

    @Provide
    Arbitrary<List<Integer>> gapsMs() {
        return Arbitraries.oneOf(Arbitraries.integers().between(0, 50), Arbitraries.integers().between(0, 5000))
                .list().ofMinSize(1).ofMaxSize(400);
    }

    /** Never more than RPM admissions in any 60 s window, for any arrival pattern. */
    @Property(tries = 300)
    void neverExceedsLimitInAnyWindow(@ForAll @IntRange(min = 1, max = 30) int rpm, @ForAll("gapsMs") List<Integer> gaps) {
        MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
        LocalRateLimiter limiter = new LocalRateLimiter(clock);
        List<Long> admitted = new ArrayList<>();
        for (int gap : gaps) {
            clock.advance(Duration.ofMillis(gap));
            if (limiter.tryAcquire("t", rpm, "r").allowed()) {
                admitted.add(clock.millis());
            }
        }
        assertMaxInWindow(admitted, rpm);
    }

    static void assertMaxInWindow(List<Long> admitted, int rpm) {
        int start = 0;
        for (int end = 0; end < admitted.size(); end++) {
            while (admitted.get(end) - admitted.get(start) >= 60_000) {
                start++;
            }
            assertThat(end - start + 1).isLessThanOrEqualTo(rpm);
        }
    }
}
