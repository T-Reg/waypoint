package dev.waypoint.gateway.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.routing.RouteTable;
import dev.waypoint.gateway.support.Fixtures;
import dev.waypoint.gateway.tenant.Tenant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class SnapshotSwapProperties {

    /** Every route and tenant in a snapshot carries its revision, so a mixed view would be visible. */
    static ConfigSnapshot snapshot(long revision, int routes) {
        RouteTable.Builder table = RouteTable.builder();
        Map<String, Tenant> tenants = new HashMap<>();
        for (int i = 0; i < routes; i++) {
            Route route = Fixtures.route("r" + i, "/p" + i, Map.of("*", List.of()));
            table.add(new Route(route.id(), route.pathPrefix(), route.upstreams(), false, route.requiredScopes(), null, true,
                    "rev-" + revision, 1000, true));
            tenants.put("t" + i, new Tenant("t" + i, List.of("/p" + i), (int) (revision % 1000) + 1, false, false));
        }
        return new ConfigSnapshot(revision, table.build(), tenants, List.of());
    }

    @Property(tries = 20)
    void readersAlwaysSeeAConsistentSnapshot(@ForAll @IntRange(min = 1, max = 20) int routes,
                                             @ForAll @IntRange(min = 1, max = 4) int writers) throws Exception {
        SnapshotHolder holder = new SnapshotHolder();
        holder.swapIfNewer(snapshot(0, routes));
        AtomicBoolean done = new AtomicBoolean();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers + 4);
        try {
            for (int w = 0; w < writers; w++) {
                int offset = w;
                pool.submit(() -> {
                    start.await();
                    for (long rev = 1 + offset; rev < 400; rev += writers) {
                        holder.swapIfNewer(snapshot(rev, routes));
                    }
                    return null;
                });
            }
            List<Future<Long>> readers = new java.util.ArrayList<>();
            for (int r = 0; r < 4; r++) {
                readers.add(pool.submit(() -> {
                    start.await();
                    long reads = 0;
                    long lastRevision = -1;
                    while (!done.get() || reads < 100) {
                        ConfigSnapshot s = holder.get();
                        String expectedAudience = "rev-" + s.revision();
                        assertThat(s.routes().routes().values()).allMatch(route -> route.audience().equals(expectedAudience));
                        assertThat(s.tenants().values()).allMatch(t -> t.requestsPerMinute() == (int) (s.revision() % 1000) + 1);
                        assertThat(s.revision()).isGreaterThanOrEqualTo(lastRevision);
                        lastRevision = s.revision();
                        reads++;
                    }
                    return reads;
                }));
            }
            start.countDown();
            pool.shutdown();
            Thread.ofVirtual().start(() -> {
                try {
                    while (holder.get().revision() < 399 - writers) {
                        Thread.onSpinWait();
                    }
                } finally {
                    done.set(true);
                }
            }).join(TimeUnit.SECONDS.toMillis(30));
            done.set(true);
            for (Future<Long> reader : readers) {
                assertThat(reader.get(30, TimeUnit.SECONDS)).isPositive();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Property(tries = 50)
    void neverSwapsBackwards(@ForAll @IntRange(min = 0, max = 100) int first, @ForAll @IntRange(min = 0, max = 100) int second) {
        SnapshotHolder holder = new SnapshotHolder();
        assertThat(holder.isLoaded()).isFalse();
        holder.swapIfNewer(snapshot(first, 1));
        boolean swapped = holder.swapIfNewer(snapshot(second, 1));
        assertThat(swapped).isEqualTo(second > first);
        assertThat(holder.get().revision()).isEqualTo(Math.max(first, second));
        assertThat(holder.isLoaded()).isTrue();
    }
}
