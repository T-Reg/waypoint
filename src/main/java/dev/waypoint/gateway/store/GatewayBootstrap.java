package dev.waypoint.gateway.store;

import dev.waypoint.gateway.identity.KeyService;
import dev.waypoint.gateway.ratelimit.RateLimiter;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.NestedExceptionUtils;

/**
 * Brings the instance to ready: migrate, seed if empty, load the snapshot, register a key, start
 * sync, connect the shared rate limiter. Retries with backoff and stays not-ready meanwhile; it
 * never serves a partial table.
 */
public class GatewayBootstrap implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GatewayBootstrap.class);

    private final Migrator migrator;
    private final Seeder seeder;
    private final PostgresConfigStore store;
    private final SnapshotBuilder builder;
    private final SnapshotHolder holder;
    private final KeyService keys;
    private final ConfigSync sync;
    private final RateLimiter rateLimiter;
    private final GatewayState state;
    private final Duration retryMax;
    private volatile boolean stopped;
    private volatile Thread worker;

    public GatewayBootstrap(Migrator migrator, Seeder seeder, PostgresConfigStore store, SnapshotBuilder builder,
                            SnapshotHolder holder, KeyService keys, ConfigSync sync, RateLimiter rateLimiter, GatewayState state,
                            Duration retryMax) {
        this.migrator = migrator;
        this.seeder = seeder;
        this.store = store;
        this.builder = builder;
        this.holder = holder;
        this.keys = keys;
        this.sync = sync;
        this.rateLimiter = rateLimiter;
        this.state = state;
        this.retryMax = retryMax;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (worker == null) {
            worker = Thread.ofVirtual().name("gateway-bootstrap").start(this::run);
        }
    }

    void run() {
        long backoffMs = 100;
        while (!stopped) {
            try {
                migrator.migrate();
                seeder.seedIfEmpty();
                holder.swapIfNewer(builder.build(store.loadSnapshot()));
                keys.initialize(store.keyGeneration());
                sync.start();
                rateLimiter.warmUp();
                state.markBootstrapped();
                log.info("Gateway ready at config revision {}", holder.get().revision());
                return;
            } catch (Exception e) {
                log.warn("Startup not complete, retrying in {} ms: {}", backoffMs, describe(e));
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, retryMax.toMillis());
            }
        }
    }

    // Wrappers such as "Failed to obtain JDBC Connection" hide the actionable cause (auth, TLS, network).
    static String describe(Throwable e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        if (root == e) {
            return e.getMessage();
        }
        return e.getMessage() + " (cause: " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
    }

    @Override
    public void destroy() {
        stopped = true;
        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
        sync.stop();
        if (state.isBootstrapped()) {
            keys.retireOnShutdown();
        }
    }
}
