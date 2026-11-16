package dev.waypoint.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.waypoint.gateway.support.Containers;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The Lua script against a real Valkey, with explicit {@code now_ms} so time is deterministic. */
class ValkeyRateLimiterIT {

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static ValkeyRateLimiter limiter;
    static final long T0 = 1_700_000_000_000L;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(Containers.VALKEY.getHost(), Containers.VALKEY.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        limiter = new ValkeyRateLimiter(redis);
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @BeforeEach
    void flush() {
        Containers.flushValkey();
    }

    static RateDecision at(String tenant, int limit, long now) {
        return limiter.tryAcquire(tenant, limit, UUID.randomUUID().toString(), now);
    }

    @Test
    void burstsUpToCapacityThenDenies() {
        for (int i = 0; i < 5; i++) {
            assertThat(at("burst", 5, T0).allowed()).isTrue();
        }
        assertThat(at("burst", 5, T0).allowed()).isFalse();
    }

    @Test
    void capacityFreesAsEntriesAgeOutAndRetryAfterIsExact() {
        assertThat(at("refill", 2, T0).allowed()).isTrue();
        assertThat(at("refill", 2, T0 + 10_000).allowed()).isTrue();
        RateDecision denied = at("refill", 2, T0 + 20_000);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).isEqualTo(40_000);
        assertThat(at("refill", 2, T0 + 59_999).allowed()).isFalse();
        assertThat(at("refill", 2, T0 + 60_000).allowed()).as("exactly when Retry-After said").isTrue();
        assertThat(at("refill", 2, T0 + 60_001).allowed()).isFalse();
        assertThat(at("refill", 2, T0 + 70_000).allowed()).isTrue();
    }

    @Test
    void keysExpireAfterTheWindow() {
        at("expiry", 3, T0);
        Long ttl = redis.getExpire("rl:expiry", java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(1L, 60_000L);
    }

    @Test
    void usesServerTimeWhenNoOverride() {
        assertThat(limiter.tryAcquire("server-time", 1, "a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("server-time", 1, "b").allowed()).isFalse();
    }

    @Test
    void tenantsAreIsolated() {
        assertThat(at("iso-a", 1, T0).allowed()).isTrue();
        assertThat(at("iso-b", 1, T0).allowed()).isTrue();
        assertThat(at("iso-a", 1, T0).allowed()).isFalse();
    }

    @Test
    void warmUpConnectsToValkeyAndFailsWhenUnreachable() {
        limiter.warmUp();
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory(Containers.VALKEY.getHost(), 1);
        nowhere.afterPropertiesSet();
        try {
            assertThatThrownBy(new ValkeyRateLimiter(new StringRedisTemplate(nowhere))::warmUp)
                    .isInstanceOf(RedisConnectionFailureException.class);
        } finally {
            nowhere.destroy();
        }
    }
}
