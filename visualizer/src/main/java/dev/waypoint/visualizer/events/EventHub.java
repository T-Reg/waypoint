package dev.waypoint.visualizer.events;

import dev.waypoint.visualizer.traffic.RequestResult;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Fans events out to every open browser tab over SSE and keeps recent results for page reloads. */
@Component
public class EventHub {

    private static final int RECENT_LIMIT = 300;
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Deque<RequestResult> recent = new ArrayDeque<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            // Commits the response now, so the browser's EventSource opens without waiting for traffic.
            emitter.send(SseEmitter.event().name("hello").data("ok"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void publishRequest(RequestResult result) {
        synchronized (recent) {
            recent.addLast(result);
            if (recent.size() > RECENT_LIMIT) {
                recent.removeFirst();
            }
        }
        publish("request", result);
    }

    public List<RequestResult> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    public void publish(String name, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().name(name).data(data));
                }
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    int subscriberCount() {
        return emitters.size();
    }
}
