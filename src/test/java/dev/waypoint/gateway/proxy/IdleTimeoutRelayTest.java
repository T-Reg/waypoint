package dev.waypoint.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IdleTimeoutRelayTest {

    static final Duration IDLE = Duration.ofMillis(300);

    final ManualWatchdog watchdog = new ManualWatchdog();
    final IdleTimeoutRelay relay = new IdleTimeoutRelay(watchdog);

    @Test
    void relaysEveryChunkAndDisarmsEachAlarm() throws IOException {
        byte[] body = new byte[40 * 1024];
        Arrays.fill(body, (byte) 'x');
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        relay.copy(new ByteArrayInputStream(body), out, IDLE);

        assertThat(out.toByteArray()).isEqualTo(body);
        assertThat(watchdog.alarms).hasSize(4).allSatisfy(alarm -> {
            assertThat(alarm.isCancelled()).isTrue();
            assertThat(alarm.getDelay(TimeUnit.MILLISECONDS)).isEqualTo(IDLE.toMillis());
        });
    }

    @Test
    void stallClosesTheBodyAndReportsIdleTimeout() {
        StallingBody body = new StallingBody(watchdog, "first".getBytes());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThatThrownBy(() -> relay.copy(body, out, IDLE))
                .isInstanceOf(IdleTimeoutRelay.IdleTimeoutException.class)
                .hasMessageContaining("300ms");
        assertThat(body.closed).isTrue();
        assertThat(out.toString()).isEqualTo("first");
    }

    @Test
    void otherReadFailuresAreReportedAsUpstreamReadFailures() {
        IOException reset = new IOException("connection reset");
        InputStream body = new InputStream() {
            @Override
            public int read() throws IOException {
                throw reset;
            }
        };

        assertThatThrownBy(() -> relay.copy(body, new ByteArrayOutputStream(), IDLE))
                .isExactlyInstanceOf(IdleTimeoutRelay.UpstreamReadException.class)
                .hasCause(reset);
        assertThat(watchdog.alarms).singleElement().satisfies(alarm -> assertThat(alarm.isCancelled()).isTrue());
    }

    @Test
    void clientWriteFailuresPassThroughUnchanged() {
        IOException brokenPipe = new IOException("broken pipe");
        OutputStream client = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw brokenPipe;
            }
        };

        assertThatThrownBy(() -> relay.copy(new ByteArrayInputStream("data".getBytes()), client, IDLE)).isSameAs(brokenPipe);
    }

    @Test
    void closeStopsTheWatchdog() {
        relay.close();
        assertThat(watchdog.isShutdown()).isTrue();
    }

    /** Records alarms instead of timing them; a test fires one to simulate the idle timeout elapsing. */
    static final class ManualWatchdog extends ScheduledThreadPoolExecutor {

        final List<Alarm> alarms = new ArrayList<>();

        ManualWatchdog() {
            super(0);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            Alarm alarm = new Alarm(task, unit.toMillis(delay));
            alarms.add(alarm);
            return alarm;
        }

        void fireLatest() {
            alarms.getLast().run();
        }
    }

    static final class Alarm extends FutureTask<Void> implements ScheduledFuture<Void> {

        private final long delayMillis;

        Alarm(Runnable task, long delayMillis) {
            super(task, null);
            this.delayMillis = delayMillis;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(delayMillis, other.getDelay(TimeUnit.MILLISECONDS));
        }
    }

    /** Returns one chunk, then blocks until the watchdog closes it, as the JDK client's body stream does. */
    static final class StallingBody extends InputStream {

        private final ManualWatchdog watchdog;
        private byte[] chunk;
        volatile boolean closed;

        StallingBody(ManualWatchdog watchdog, byte[] chunk) {
            this.watchdog = watchdog;
            this.chunk = chunk;
        }

        @Override
        public int read() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (chunk != null) {
                System.arraycopy(chunk, 0, buffer, offset, chunk.length);
                int n = chunk.length;
                chunk = null;
                return n;
            }
            watchdog.fireLatest();
            if (closed) {
                throw new IOException("closed");
            }
            return -1;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
