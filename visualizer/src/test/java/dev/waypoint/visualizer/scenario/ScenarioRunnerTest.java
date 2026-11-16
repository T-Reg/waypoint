package dev.waypoint.visualizer.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.waypoint.visualizer.events.EventHub;
import dev.waypoint.visualizer.scenario.Scenario.Step;
import dev.waypoint.visualizer.support.TestSupport;
import dev.waypoint.visualizer.token.TokenFault;
import dev.waypoint.visualizer.traffic.RequestResult;
import dev.waypoint.visualizer.traffic.RequestResult.Outcome;
import dev.waypoint.visualizer.traffic.RequestResult.Upstream;
import dev.waypoint.visualizer.traffic.RequestSender;
import dev.waypoint.visualizer.traffic.RequestSpec;
import dev.waypoint.visualizer.traffic.Stage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ScenarioRunnerTest {

    /** Answers 200 until {@code allowed} requests have passed, then 429, like the gateway's quota. */
    static final class FakeSender implements RequestSender {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicInteger sent = new AtomicInteger();
        final AtomicLong seq = new AtomicLong();
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final int allowed;
        volatile CountDownLatch gate;

        FakeSender(int allowed) {
            this.allowed = allowed;
        }

        @Override
        public RequestResult send(RequestSpec spec, String runId, String scenarioId) {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                if (gate != null) {
                    gate.await(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            order.add(spec.path());
            boolean ok = sent.incrementAndGet() <= allowed;
            return new RequestResult(seq.incrementAndGet(), runId, scenarioId, TestSupport.NOW, spec.target(),
                    spec.target().equals("lb") ? "gateway-" + (1 + seq.get() % 2) : spec.target(), spec.method(), spec.path(),
                    spec.tenant(), spec.fault().name(), ok ? 200 : 429, ok ? Outcome.ALLOWED : Outcome.REJECTED,
                    ok ? Stage.UPSTREAM : Stage.QUOTA, ok ? null : "policy.quota_exceeded", null, ok ? null : "30", null,
                    "r", 1, Map.of(), "", ok ? new Upstream(true, true, "/", Map.of(), null) : Upstream.notReceived());
        }

        @Override
        public void checkTarget(String target) {
            if (!List.of("lb", "gateway-1", "gateway-2").contains(target)) {
                throw new IllegalArgumentException("unknown target");
            }
        }
    }

    static final class RecordingHub extends EventHub {
        final List<Object> runs = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger requests = new AtomicInteger();

        @Override
        public void publish(String name, Object data) {
            if (name.equals("run")) {
                runs.add(data);
            }
        }

        @Override
        public void publishRequest(RequestResult result) {
            requests.incrementAndGet();
        }
    }

    private final List<Duration> pauses = Collections.synchronizedList(new ArrayList<>());
    private final RecordingHub hub = new RecordingHub();

    private ScenarioRunner runner(FakeSender sender) {
        return new ScenarioRunner(sender, hub, pauses::add, TestSupport.properties("http://gw", "http://up", "http://jwks"));
    }

    private static RequestSpec spec(String target, String path) {
        return new RequestSpec(target, "GET", path, "globex", null, "weather:read", TokenFault.NONE, Map.of(), null);
    }

    @Test
    void burstSummaryCountsAllowedAndRejected() {
        FakeSender sender = new FakeSender(60);

        RunUpdate update = runner(sender).start("burst", "Burst", "60 × 200, 20 × 429",
                List.of(Step.burst("b", spec("lb", "/weather"), 80, 16)), false).finished().join();

        assertThat(update.phase()).isEqualTo(RunUpdate.Phase.FINISHED);
        assertThat(update.total()).isEqualTo(80);
        assertThat(update.completed()).isEqualTo(80);
        assertThat(update.byStatus()).containsEntry("200", 60L).containsEntry("429", 20L);
        assertThat(update.byServedBy().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(80);
        assertThat(hub.requests).hasValue(80);
        assertThat(hub.runs).hasSize(2);
        assertThat(((RunUpdate) hub.runs.getFirst()).phase()).isEqualTo(RunUpdate.Phase.STARTED);
        assertThat(sender.maxInFlight.get()).isLessThanOrEqualTo(16);
        assertThat(pauses).hasSize(79).allMatch(Duration::isZero);
    }

    @Test
    void concurrencyIsCappedByConfiguration() throws Exception {
        FakeSender sender = new FakeSender(1000);
        sender.gate = new CountDownLatch(1);

        var run = runner(sender).start("c", "Cap", null, List.of(Step.burst("b", spec("lb", "/x"), 60, 100)), false);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> sender.inFlight.get() == 20);
        sender.gate.countDown();
        run.finished().join();

        assertThat(sender.maxInFlight.get()).isEqualTo(20);
    }

    @Test
    void sequentialStepsRunInOrderWithPacing() {
        FakeSender sender = new FakeSender(100);
        Scenario tour = new Scenario("tour", "Tour", "c", "s", "w", "e", false, List.of(
                Step.paced("first", spec("lb", "/1"), 2, 250),
                Step.once("second", spec("gateway-1", "/2")),
                Step.once("third", spec("gateway-2", "/3"))));

        RunUpdate update = runner(sender).start(tour).finished().join();

        assertThat(sender.order).containsExactly("/1", "/1", "/2", "/3");
        assertThat(pauses).containsExactly(Duration.ofMillis(250), Duration.ofMillis(700), Duration.ofMillis(700));
        assertThat(update.scenarioId()).isEqualTo("tour");
        assertThat(update.byServedBy()).containsKeys("gateway-1", "gateway-2");
    }

    @Test
    void parallelStepsOverlap() throws Exception {
        FakeSender sender = new FakeSender(100);
        sender.gate = new CountDownLatch(1);
        Scenario shared = new Scenario("shared", "Shared", "c", "s", "w", "e", true, List.of(
                Step.burst("g1", spec("gateway-1", "/a"), 1, 1),
                Step.burst("g2", spec("gateway-2", "/b"), 1, 1)));

        var run = runner(sender).start(shared);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> sender.inFlight.get() == 2);
        sender.gate.countDown();

        assertThat(run.finished().join().completed()).isEqualTo(2);
    }

    @Test
    void refusesMoreThanThreeConcurrentRuns() {
        FakeSender sender = new FakeSender(100);
        sender.gate = new CountDownLatch(1);
        ScenarioRunner runner = runner(sender);
        List<ScenarioRunner.RunHandle> runs = new ArrayList<>();
        for (int i = 0; i < ScenarioRunner.MAX_ACTIVE_RUNS; i++) {
            runs.add(runner.start(null, "r", null, List.of(Step.burst("b", spec("lb", "/x"), 1, 1)), false));
        }

        assertThatThrownBy(() -> runner.start(null, "r", null, List.of(Step.burst("b", spec("lb", "/x"), 1, 1)), false))
                .isInstanceOf(ScenarioRunner.BusyException.class);

        sender.gate.countDown();
        runs.forEach(run -> run.finished().join());
        assertThat(runner.start(null, "r", null, List.of(Step.burst("b", spec("lb", "/x"), 1, 1)), false).finished().join()
                .completed()).isEqualTo(1);
    }

    @Test
    void validatesSizeAndTargetsBeforeStarting() {
        ScenarioRunner runner = runner(new FakeSender(1));

        assertThatThrownBy(() -> runner.start(null, "big", null, List.of(Step.burst("b", spec("lb", "/x"), 201, 1)), false))
                .hasMessageContaining("at most 200");
        assertThatThrownBy(() -> runner.start(null, "bad", null, List.of(Step.burst("b", spec("gateway-9", "/x"), 1, 1)), false))
                .hasMessageContaining("unknown target");
        assertThat(hub.runs).isEmpty();
    }

    @Test
    void summaryLabelsMissingResponses() {
        RequestResult noResponse = new RequestResult(1, "r", null, TestSupport.NOW, "lb", null, "GET", "/", "acme", "NONE", 0,
                Outcome.ERROR, Stage.LOAD_BALANCER, null, "down", null, null, null, 0, Map.of(), null, Upstream.unchecked());

        RunUpdate update = ScenarioRunner.summarize("r", null, "t", null, 1, List.of(noResponse), 5);

        assertThat(update.byStatus()).containsEntry("no response", 1L);
        assertThat(update.byServedBy()).isEmpty();
    }
}
