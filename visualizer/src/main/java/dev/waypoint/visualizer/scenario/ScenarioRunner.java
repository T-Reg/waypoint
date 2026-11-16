package dev.waypoint.visualizer.scenario;

import dev.waypoint.visualizer.config.VisualizerProperties;
import dev.waypoint.visualizer.events.EventHub;
import dev.waypoint.visualizer.scenario.Scenario.Step;
import dev.waypoint.visualizer.traffic.RequestResult;
import dev.waypoint.visualizer.traffic.RequestSender;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Runs scenarios and ad-hoc bursts on virtual threads, streaming each result as it completes. */
@Component
public class ScenarioRunner {

    static final int MAX_ACTIVE_RUNS = 3;

    private final RequestSender caller;
    private final EventHub events;
    private final Pacer pacer;
    private final int maxConcurrency;
    private final int maxRequestsPerRun;
    private final Semaphore activeRuns = new Semaphore(MAX_ACTIVE_RUNS);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ScenarioRunner(RequestSender caller, EventHub events, Pacer pacer, VisualizerProperties properties) {
        this.caller = caller;
        this.events = events;
        this.pacer = pacer;
        this.maxConcurrency = properties.maxConcurrency();
        this.maxRequestsPerRun = properties.maxRequestsPerRun();
    }

    public record RunHandle(String runId, CompletableFuture<RunUpdate> finished) {
    }

    /** Thrown when too many runs are already in flight; the API maps it to 429. */
    public static class BusyException extends RuntimeException {
        BusyException() {
            super("too many runs in progress; wait for one to finish");
        }
    }

    public RunHandle start(Scenario scenario) {
        return start(scenario.id(), scenario.title(), scenario.expect(), scenario.steps(), scenario.parallel());
    }

    public RunHandle start(String scenarioId, String title, String expect, List<Step> steps, boolean parallel) {
        int total = steps.stream().mapToInt(Step::count).sum();
        if (total > maxRequestsPerRun) {
            throw new IllegalArgumentException("a run may send at most " + maxRequestsPerRun + " requests");
        }
        steps.forEach(step -> caller.checkTarget(step.request().target()));
        if (!activeRuns.tryAcquire()) {
            throw new BusyException();
        }
        String runId = UUID.randomUUID().toString();
        events.publish("run", new RunUpdate(runId, scenarioId, title, expect, RunUpdate.Phase.STARTED, total, 0,
                Map.of(), Map.of(), 0));
        ConcurrentLinkedQueue<RequestResult> results = new ConcurrentLinkedQueue<>();
        long started = System.nanoTime();
        CompletableFuture<RunUpdate> finished = CompletableFuture.supplyAsync(() -> {
            try {
                if (parallel) {
                    CompletableFuture.allOf(steps.stream()
                            .map(step -> CompletableFuture.runAsync(() -> runStep(runId, scenarioId, step, results, false), executor))
                            .toArray(CompletableFuture[]::new)).join();
                } else {
                    for (int i = 0; i < steps.size(); i++) {
                        runStep(runId, scenarioId, steps.get(i), results, i > 0);
                    }
                }
                RunUpdate update = summarize(runId, scenarioId, title, expect, total, List.copyOf(results),
                        (System.nanoTime() - started) / 1_000_000);
                events.publish("run", update);
                return update;
            } finally {
                activeRuns.release();
            }
        }, executor);
        return new RunHandle(runId, finished);
    }

    /** {@code pauseFirst} also spaces this step from the previous one in a sequential scenario. */
    private void runStep(String runId, String scenarioId, Step step, ConcurrentLinkedQueue<RequestResult> results,
                         boolean pauseFirst) {
        Semaphore inFlight = new Semaphore(Math.min(step.concurrency(), maxConcurrency));
        CompletableFuture<?>[] requests = new CompletableFuture<?>[step.count()];
        try {
            for (int i = 0; i < step.count(); i++) {
                if (i > 0 || pauseFirst) {
                    pacer.pause(step.interval());
                }
                inFlight.acquire();
                requests[i] = CompletableFuture.runAsync(() -> {
                    try {
                        RequestResult result = caller.send(step.request(), runId, scenarioId);
                        results.add(result);
                        events.publishRequest(result);
                    } finally {
                        inFlight.release();
                    }
                }, executor);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (CompletableFuture<?> request : requests) {
            if (request != null) {
                request.join();
            }
        }
    }

    static RunUpdate summarize(String runId, String scenarioId, String title, String expect, int total,
                               List<RequestResult> results, long durationMs) {
        Map<String, Long> byStatus = results.stream().collect(Collectors.groupingBy(
                r -> r.status() == 0 ? "no response" : String.valueOf(r.status()), TreeMap::new, Collectors.counting()));
        Map<String, Long> byServedBy = results.stream().filter(r -> r.servedBy() != null).collect(Collectors.groupingBy(
                RequestResult::servedBy, TreeMap::new, Collectors.counting()));
        return new RunUpdate(runId, scenarioId, title, expect, RunUpdate.Phase.FINISHED, total, results.size(),
                byStatus, byServedBy, durationMs);
    }
}
