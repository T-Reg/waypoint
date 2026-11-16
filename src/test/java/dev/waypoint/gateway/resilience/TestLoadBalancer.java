package dev.waypoint.gateway.resilience;

import dev.waypoint.gateway.support.TestGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Round-robin LB that, like HAProxy, only routes to backends whose readiness probe last passed. */
final class TestLoadBalancer implements AutoCloseable {

    private final List<TestGateway> backends = new CopyOnWriteArrayList<>();
    private final Map<TestGateway, Boolean> healthy = new ConcurrentHashMap<>();
    private final AtomicInteger next = new AtomicInteger();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService prober = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    final AtomicLong ok = new AtomicLong();
    final List<String> failures = new CopyOnWriteArrayList<>();

    TestLoadBalancer(Duration probeInterval) {
        prober.scheduleWithFixedDelay(() -> backends.forEach(b -> healthy.put(b, b.isReady())),
                0, probeInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void add(TestGateway backend) {
        backends.add(backend);
    }

    void remove(TestGateway backend) {
        backends.remove(backend);
        healthy.remove(backend);
    }

    void send(String path, String token) {
        List<TestGateway> up = backends.stream().filter(b -> healthy.getOrDefault(b, false)).toList();
        if (up.isEmpty()) {
            failures.add("no healthy backend");
            return;
        }
        TestGateway target = up.get(Math.floorMod(next.getAndIncrement(), up.size()));
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target.publicPort() + path))
                    .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                ok.incrementAndGet();
            } else {
                failures.add(target.name() + " -> " + response.statusCode() + " " + response.body());
            }
        } catch (Exception e) {
            failures.add(target.name() + " -> " + e);
        }
    }

    @Override
    public void close() {
        prober.shutdownNow();
    }
}
