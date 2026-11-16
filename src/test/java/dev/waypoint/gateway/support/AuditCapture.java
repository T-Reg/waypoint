package dev.waypoint.gateway.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.slf4j.LoggerFactory;

/** Captures the real JSON lines written to the {@code audit} logger. */
public final class AuditCapture implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger logger = (Logger) LoggerFactory.getLogger("audit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public AuditCapture() {
        appender.start();
        logger.addAppender(appender);
    }

    public List<String> lines() {
        synchronized (appender.list) {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    public List<JsonNode> entries() {
        return lines().stream().map(AuditCapture::parse).toList();
    }

    public Optional<JsonNode> forRequest(String requestId) {
        return entries().stream().filter(e -> requestId.equals(e.path("request_id").asString())).findFirst();
    }

    public void clear() {
        synchronized (appender.list) {
            appender.list.clear();
        }
    }

    private static JsonNode parse(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (Exception e) {
            throw new IllegalStateException("audit line is not JSON: " + line, e);
        }
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
    }
}
