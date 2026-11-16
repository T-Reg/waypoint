package dev.waypoint.gateway.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes one JSON line per entry to the {@code audit} logger; Jackson escaping prevents log injection. */
public class JsonAuditSink implements AuditSink {

    private static final Logger audit = LoggerFactory.getLogger("audit");
    private static final Logger log = LoggerFactory.getLogger(JsonAuditSink.class);

    private final ObjectMapper mapper;

    public JsonAuditSink(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void write(AuditEntry entry) {
        try {
            audit.info(mapper.writeValueAsString(entry));
        } catch (JacksonException e) {
            log.error("Could not serialize audit entry {}", entry.requestId(), e);
        }
    }
}
