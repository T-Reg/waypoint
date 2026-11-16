package dev.waypoint.visualizer.api;

import dev.waypoint.visualizer.config.VisualizerProperties;
import dev.waypoint.visualizer.events.EventHub;
import dev.waypoint.visualizer.health.HealthPoller;
import dev.waypoint.visualizer.health.ServiceStatus;
import dev.waypoint.visualizer.scenario.Scenario;
import dev.waypoint.visualizer.scenario.ScenarioCatalog;
import dev.waypoint.visualizer.scenario.ScenarioRunner;
import dev.waypoint.visualizer.token.TokenFault;
import dev.waypoint.visualizer.traffic.RequestResult;
import dev.waypoint.visualizer.traffic.RequestSender;
import dev.waypoint.visualizer.traffic.RequestSpec;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final VisualizerProperties properties;
    private final ScenarioCatalog catalog;
    private final ScenarioRunner runner;
    private final RequestSender caller;
    private final EventHub events;
    private final HealthPoller health;

    public ApiController(VisualizerProperties properties, ScenarioCatalog catalog, ScenarioRunner runner,
                         RequestSender caller, EventHub events, HealthPoller health) {
        this.properties = properties;
        this.catalog = catalog;
        this.runner = runner;
        this.caller = caller;
        this.events = events;
        this.health = health;
    }

    public record FaultOption(String id, String description) {
    }

    public record ClientConfig(List<String> targets, String defaultTarget, String loadBalancerName, List<String> tenants,
                               List<FaultOption> faults, List<Scenario> scenarios, int maxRequestsPerRun, int maxConcurrency) {
    }

    /** Body of POST /api/requests: one request, optionally repeated as a burst. */
    public record SendRequest(RequestSpec request, Integer count, Integer concurrency) {
    }

    public record RunStarted(String runId) {
    }

    @GetMapping("/config")
    public ClientConfig config() {
        List<FaultOption> faults = Arrays.stream(TokenFault.values()).map(f -> new FaultOption(f.name(), f.description())).toList();
        return new ClientConfig(List.copyOf(new TreeSet<>(properties.targets().keySet())), properties.defaultTarget(),
                properties.loadBalancerName(), properties.allowedTenants(), faults, catalog.all(), properties.maxRequestsPerRun(),
                properties.maxConcurrency());
    }

    @GetMapping("/health")
    public List<ServiceStatus> health() {
        return health.latest();
    }

    @GetMapping("/requests")
    public List<RequestResult> recent() {
        return events.recent();
    }

    @GetMapping(path = "/events", produces = "text/event-stream")
    public SseEmitter events() {
        return events.subscribe();
    }

    @PostMapping("/requests")
    public ResponseEntity<?> send(@RequestBody SendRequest body) {
        if (body.request() == null) {
            throw new IllegalArgumentException("request is required");
        }
        RequestSpec spec = body.request().target() == null
                ? body.request().withTarget(properties.defaultTarget()) : body.request();
        int count = body.count() == null ? 1 : body.count();
        int concurrency = body.concurrency() == null ? 1 : body.concurrency();
        if (count < 1 || concurrency < 1) {
            throw new IllegalArgumentException("count and concurrency must be positive");
        }
        if (count == 1) {
            RequestResult result = caller.send(spec, null, null);
            events.publishRequest(result);
            return ResponseEntity.ok(result);
        }
        String title = count + " × " + spec.method() + " " + spec.path() + " as " + spec.tenant();
        ScenarioRunner.RunHandle run = runner.start(null, title, null,
                List.of(Scenario.Step.burst(title, spec, count, concurrency)), false);
        return ResponseEntity.accepted().body(new RunStarted(run.runId()));
    }

    @PostMapping("/scenarios/{id}/run")
    public ResponseEntity<RunStarted> run(@PathVariable String id) {
        Scenario scenario = catalog.find(id).orElseThrow(() -> new UnknownScenarioException(id));
        return ResponseEntity.accepted().body(new RunStarted(runner.start(scenario).runId()));
    }

    static class UnknownScenarioException extends RuntimeException {
        UnknownScenarioException(String id) {
            super("no scenario named " + id);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(UnknownScenarioException.class)
    public ResponseEntity<Map<String, Object>> notFound(UnknownScenarioException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ScenarioRunner.BusyException.class)
    public ResponseEntity<Map<String, Object>> busy(ScenarioRunner.BusyException e) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status)
                .header("Content-Type", "application/problem+json")
                .body(Map.of("title", status.getReasonPhrase(), "status", status.value(), "detail", detail));
    }
}
