package dev.waypoint.gateway.store;

import dev.waypoint.gateway.identity.KeyService;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps this instance's snapshot and keys converged with PostgreSQL: push via LISTEN, with a poll
 * fallback for missed notifications, dropped connections and failover.
 */
public class ConfigSync {

    private static final Logger log = LoggerFactory.getLogger(ConfigSync.class);
    private static final int NOTIFICATION_WAIT_MS = 500;
    private static final int VALIDATE_EVERY = 10;

    private final PostgresConfigStore store;
    private final SnapshotBuilder builder;
    private final SnapshotHolder holder;
    private final KeyService keys;
    private final GatewayState state;
    private final ListenConnectionFactory listenFactory;
    private final Duration pollInterval;
    private final Duration heartbeatInterval;
    private final Duration reconnectMax;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile boolean running;
    private ScheduledExecutorService scheduler;
    private Thread listener;

    public ConfigSync(PostgresConfigStore store, SnapshotBuilder builder, SnapshotHolder holder, KeyService keys,
                      GatewayState state, ListenConnectionFactory listenFactory,
                      Duration pollInterval, Duration heartbeatInterval, Duration reconnectMax) {
        this.store = store;
        this.builder = builder;
        this.holder = holder;
        this.keys = keys;
        this.state = state;
        this.listenFactory = listenFactory;
        this.pollInterval = pollInterval;
        this.heartbeatInterval = heartbeatInterval;
        this.reconnectMax = reconnectMax;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        scheduler = Executors.newScheduledThreadPool(2, Thread.ofVirtual().name("config-sync-", 0).factory());
        scheduler.scheduleWithFixedDelay(this::refreshQuietly, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::heartbeatQuietly, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        listener = Thread.ofVirtual().name("config-listen").start(this::listenLoop);
    }

    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (listener != null) {
            listener.interrupt();
        }
    }

    /** Reloads the snapshot if the DB revision moved, then applies any key rotation and refreshes peer keys. */
    public void refresh() {
        // A ReentrantLock, not synchronized: a virtual thread blocked on JDBC inside a monitor pins its carrier.
        refreshLock.lock();
        try {
            long revision = store.currentRevision();
            if (revision > holder.get().revision()) {
                ConfigSnapshot next = builder.build(store.loadSnapshot());
                if (holder.swapIfNewer(next)) {
                    log.info("Config snapshot swapped to revision {} ({} invalid entries)", next.revision(), next.invalidEntries().size());
                }
            }
            keys.applyGeneration(store.keyGeneration());
            keys.refreshPeers();
            state.configStoreUp(true);
        } finally {
            refreshLock.unlock();
        }
    }

    void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException e) {
            markDown("refresh", e);
        }
    }

    void heartbeatQuietly() {
        try {
            keys.heartbeat();
            keys.refreshPeers();
            state.configStoreUp(true);
        } catch (RuntimeException e) {
            markDown("heartbeat", e);
        }
    }

    private void markDown(String what, RuntimeException e) {
        if (state.isConfigStoreUp()) {
            log.warn("Config store unreachable during {}; serving last-known-good snapshot: {}", what, e.getMessage());
        }
        state.configStoreUp(false);
    }

    private void listenLoop() {
        long backoffMs = 100;
        while (running) {
            try (Connection connection = listenFactory.open(); Statement statement = connection.createStatement()) {
                statement.execute("LISTEN " + PostgresConfigStore.CHANNEL);
                state.listenConnected(true);
                backoffMs = 100;
                refreshQuietly();
                PGConnection pg = connection.unwrap(PGConnection.class);
                int iterations = 0;
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(NOTIFICATION_WAIT_MS);
                    if (notifications != null && notifications.length > 0) {
                        refreshQuietly();
                    }
                    if (++iterations % VALIDATE_EVERY == 0 && !connection.isValid(2)) {
                        throw new java.sql.SQLException("LISTEN connection is no longer valid");
                    }
                }
            } catch (Exception e) {
                state.listenConnected(false);
                if (!running) {
                    return;
                }
                log.warn("LISTEN connection lost, reconnecting in {} ms: {}", backoffMs, e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, reconnectMax.toMillis());
            }
        }
    }
}
