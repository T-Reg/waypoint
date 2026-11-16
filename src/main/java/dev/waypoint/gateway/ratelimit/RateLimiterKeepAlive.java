package dev.waypoint.gateway.ratelimit;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/**
 * Pings the rate limiter's store every {@code interval} so its connection never sits idle long enough
 * for the server or the network to drop it; a request would otherwise find it dead and degrade.
 */
public class RateLimiterKeepAlive implements SmartLifecycle {

    private final RateLimiter limiter;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    public RateLimiterKeepAlive(RateLimiter limiter, Duration interval) {
        this.limiter = limiter;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("ratelimit-keepalive").factory());
        scheduler.scheduleWithFixedDelay(limiter::keepAlive, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
