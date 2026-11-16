package dev.waypoint.gateway.store;

import java.util.concurrent.atomic.AtomicLong;

/** Lifecycle flags shared by bootstrap, sync, readiness and the public listener. */
public class GatewayState {

    private volatile boolean bootstrapped;
    private volatile boolean draining;
    private volatile boolean configStoreUp;
    private volatile boolean listenConnected;
    private final AtomicLong listenReconnects = new AtomicLong();

    public boolean isBootstrapped() {
        return bootstrapped;
    }

    public void markBootstrapped() {
        bootstrapped = true;
        configStoreUp = true;
    }

    public boolean isDraining() {
        return draining;
    }

    public void markDraining() {
        draining = true;
    }

    public boolean isConfigStoreUp() {
        return configStoreUp;
    }

    public void configStoreUp(boolean up) {
        configStoreUp = up;
    }

    public boolean isListenConnected() {
        return listenConnected;
    }

    public void listenConnected(boolean connected) {
        if (connected && !listenConnected) {
            listenReconnects.incrementAndGet();
        }
        listenConnected = connected;
    }

    /** Number of times the LISTEN connection was (re-)established. */
    public long listenConnections() {
        return listenReconnects.get();
    }
}
