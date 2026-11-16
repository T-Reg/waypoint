package dev.waypoint.gateway.store;

import java.util.concurrent.atomic.AtomicReference;

public class SnapshotHolder {

    private final AtomicReference<ConfigSnapshot> current = new AtomicReference<>(ConfigSnapshot.empty());

    public ConfigSnapshot get() {
        return current.get();
    }

    /** Swaps only forward, so a slow reload can never replace a newer snapshot. */
    public boolean swapIfNewer(ConfigSnapshot next) {
        ConfigSnapshot previous;
        do {
            previous = current.get();
            if (next.revision() <= previous.revision()) {
                return false;
            }
        } while (!current.compareAndSet(previous, next));
        return true;
    }

    public boolean isLoaded() {
        return current.get().revision() >= 0;
    }
}
