package dev.waypoint.gateway.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Copies an upstream body, closing it if a single read waits longer than the idle timeout. The request
 * timeout only covers the wait for headers; this bounds the body without capping total download time.
 */
public final class IdleTimeoutRelay implements AutoCloseable {

    private static final int BUFFER_SIZE = 16 * 1024;

    private final ScheduledExecutorService watchdog;

    public IdleTimeoutRelay() {
        this(newWatchdog());
    }

    IdleTimeoutRelay(ScheduledExecutorService watchdog) {
        this.watchdog = watchdog;
    }

    public void copy(InputStream body, OutputStream out, Duration idleTimeout) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        AtomicBoolean expired = new AtomicBoolean();
        while (true) {
            int n;
            ScheduledFuture<?> alarm = watchdog.schedule(() -> expire(body, expired), idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
            try {
                n = body.read(buffer);
            } catch (IOException e) {
                throw expired.get() ? new IdleTimeoutException(idleTimeout, e) : new UpstreamReadException(e.toString(), e);
            } finally {
                alarm.cancel(false);
            }
            if (n < 0) {
                return;
            }
            out.write(buffer, 0, n);
        }
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
    }

    private static void expire(InputStream body, AtomicBoolean expired) {
        expired.set(true);
        try {
            body.close();
        } catch (IOException ignored) {
            // The blocked reader sees the close as an IOException; nothing else to do here.
        }
    }

    private static ScheduledExecutorService newWatchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("body-idle-watchdog").daemon().factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** Reading the upstream body failed, as opposed to writing to the client. */
    public static class UpstreamReadException extends IOException {
        UpstreamReadException(String message, IOException cause) {
            super(message, cause);
        }
    }

    public static final class IdleTimeoutException extends UpstreamReadException {
        IdleTimeoutException(Duration idleTimeout, IOException cause) {
            super("upstream body idle for more than " + idleTimeout.toMillis() + "ms", cause);
        }
    }
}
