package dev.waypoint.gateway.ratelimit;

import dev.waypoint.gateway.support.Containers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** N instances sharing one Valkey key never admit more than RPM in any 60 s window. */
class ValkeySharedQuotaProperties {

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    @BeforeContainer
    static void connect() {
        factory = new LettuceConnectionFactory(Containers.VALKEY.getHost(), Containers.VALKEY.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterContainer
    static void close() {
        factory.destroy();
    }

    @Provide
    Arbitrary<List<Integer>> arrivals() {
        return Arbitraries.integers().between(0, 3000).list().ofMinSize(1).ofMaxSize(150);
    }

    @Property(tries = 25)
    void sharedAcrossInstancesNeverExceedsRpm(@ForAll @IntRange(min = 1, max = 20) int rpm, @ForAll @IntRange(min = 1, max = 5) int instances,
                                             @ForAll("arrivals") List<Integer> gaps) {
        Containers.flushValkey();
        List<ValkeyRateLimiter> fleet = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            fleet.add(new ValkeyRateLimiter(redis));
        }
        long now = ValkeyRateLimiterIT.T0;
        List<Long> admitted = new ArrayList<>();
        for (int i = 0; i < gaps.size(); i++) {
            now += gaps.get(i);
            if (fleet.get(i % instances).tryAcquire("prop", rpm, UUID.randomUUID().toString(), now).allowed()) {
                admitted.add(now);
            }
        }
        LocalRateLimiterProperties.assertMaxInWindow(admitted, rpm);
    }
}
