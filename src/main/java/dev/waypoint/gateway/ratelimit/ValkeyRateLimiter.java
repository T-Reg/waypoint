package dev.waypoint.gateway.ratelimit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** Shared quota across all instances: one atomic Lua script per request on {@code rl:<tid>}. */
public class ValkeyRateLimiter implements RateLimiter {

    public static final String KEY_PREFIX = "rl:";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(loadScript(), List.class);

    private final StringRedisTemplate redis;

    public ValkeyRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateDecision tryAcquire(String tenantId, int limit, String requestId) {
        return tryAcquire(tenantId, limit, requestId, -1);
    }

    /** Read once from this class's loader, never lazily via a request thread's context loader. */
    private static String loadScript() {
        try (InputStream in = ValkeyRateLimiter.class.getResourceAsStream("/ratelimit/sliding_window.lua")) {
            if (in == null) {
                throw new IllegalStateException("ratelimit/sliding_window.lua missing from classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void warmUp() {
        redis.execute((RedisCallback<String>) RedisConnection::ping);
    }

    @Override
    public void keepAlive() {
        warmUp();
    }

    /** {@code nowMs < 0} uses the Valkey server clock; tests pass an explicit time. */
    public RateDecision tryAcquire(String tenantId, int limit, String requestId, long nowMs) {
        List<?> result = redis.execute(SCRIPT, List.of(KEY_PREFIX + tenantId),
                Integer.toString(limit), Long.toString(WINDOW.toMillis()), Long.toString(nowMs), requestId);
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("unexpected rate-limit script result");
        }
        boolean allowed = ((Number) result.get(0)).longValue() == 1;
        return allowed ? RateDecision.allow() : RateDecision.deny(((Number) result.get(1)).longValue());
    }
}
