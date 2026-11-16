package dev.waypoint.gateway.management;

import dev.waypoint.gateway.store.GatewayState;
import java.time.Duration;
import org.springframework.context.SmartLifecycle;

/**
 * On shutdown, fail readiness first and keep serving for {@code shutdownDelay} so the load balancer
 * stops routing here before Tomcat's graceful drain begins (the preStop-sleep pattern).
 */
public class ShutdownDelay implements SmartLifecycle {

    private final GatewayState state;
    private final Duration delay;
    private volatile boolean running;

    public ShutdownDelay(GatewayState state, Duration delay) {
        this.state = state;
        this.delay = delay;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        state.markDraining();
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stops before the web server's graceful-shutdown phase. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
