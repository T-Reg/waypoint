package dev.waypoint.gateway.identity;

import com.nimbusds.jose.jwk.OctetKeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Key lifecycle across instances: register on startup, heartbeat, broadcast rotation, retire on
 * shutdown. A new key is always published in {@code gateway_keys} before it signs anything.
 */
public class KeyService {

    private static final Logger log = LoggerFactory.getLogger(KeyService.class);
    private static final Duration DELETE_AFTER = Duration.ofDays(1);

    private final GatewayKeyStore keyStore;
    private final KeyRegistry registry;
    private final Clock clock;
    private final String instanceId;
    private final Duration retention;
    private final Duration staleAfter;
    private final Duration notReadyAfter;
    private final Duration liveInstanceWindow;
    // ReentrantLock rather than synchronized: these sections do JDBC on virtual threads.
    private final ReentrantLock lock = new ReentrantLock();
    private volatile long appliedGeneration = -1;
    private volatile Instant lastHeartbeat;
    private volatile int liveInstances = 1;

    public KeyService(GatewayKeyStore keyStore, KeyRegistry registry, Clock clock, String instanceId,
                      Duration tokenTtl, Duration clockSkew, Duration heartbeatInterval, Duration staleAfter) {
        this.keyStore = keyStore;
        this.registry = registry;
        this.clock = clock;
        this.instanceId = instanceId;
        this.retention = tokenTtl.plus(clockSkew);
        this.staleAfter = staleAfter;
        // Stop taking traffic early enough that every token we minted expires before peers drop our key.
        this.notReadyAfter = staleAfter.minus(retention);
        this.liveInstanceWindow = heartbeatInterval.multipliedBy(3);
        if (notReadyAfter.compareTo(heartbeatInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("gateway.identity.key-stale-after must exceed token-ttl + clock-skew + 2 heartbeats");
        }
    }

    public void initialize(long generation) {
        lock.lock();
        try {
            if (!keyStore.hasSigningKey()) {
                OctetKeyPair key = GatewayKeyStore.generate();
                Instant now = clock.instant();
                registry.register(key, instanceId, now);
                keyStore.activate(key);
                lastHeartbeat = now;
                log.info("Registered identity key kid={} for instance {}", key.getKeyID(), instanceId);
            }
            appliedGeneration = Math.max(appliedGeneration, generation);
        } finally {
            lock.unlock();
        }
    }

    /** Rotates once per generation bump, whichever path (request, NOTIFY or poll) sees it first. */
    public boolean applyGeneration(long generation) {
        lock.lock();
        try {
            if (appliedGeneration < 0 || generation <= appliedGeneration) {
                return false;
            }
            rotate();
            appliedGeneration = generation;
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void rotate() {
        OctetKeyPair previous = keyStore.currentSigningKey();
        OctetKeyPair next = GatewayKeyStore.generate();
        Instant now = clock.instant();
        registry.register(next, instanceId, now);
        keyStore.activate(next);
        registry.retire(previous.getKeyID(), now);
        log.info("Rotated identity key {} -> {}", previous.getKeyID(), next.getKeyID());
    }

    public void heartbeat() {
        Instant now = clock.instant();
        registry.heartbeat(instanceId, now);
        lastHeartbeat = now;
        registry.deleteOlderThan(now.minus(DELETE_AFTER));
    }

    /** Refreshes peers' keys; on DB failure the caller keeps the last-known-good set. */
    public void refreshPeers() {
        Instant now = clock.instant();
        KeyRegistry.LiveKeys live = registry.liveKeys(now.minus(retention), now.minus(staleAfter), now.minus(liveInstanceWindow));
        keyStore.replacePeerKeys(live.keys());
        liveInstances = Math.max(1, live.liveInstances());
    }

    public boolean heartbeatFresh() {
        Instant last = lastHeartbeat;
        return last != null && !clock.instant().isAfter(last.plus(notReadyAfter));
    }

    public int liveInstances() {
        return liveInstances;
    }

    public String currentKid() {
        return keyStore.hasSigningKey() ? keyStore.currentSigningKey().getKeyID() : null;
    }

    public long appliedGeneration() {
        return appliedGeneration;
    }

    public void retireOnShutdown() {
        try {
            registry.retireInstance(instanceId, clock.instant());
        } catch (RuntimeException e) {
            log.warn("Could not mark keys retired on shutdown; they expire via heartbeat staleness: {}", e.getMessage());
        }
    }
}
