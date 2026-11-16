package dev.waypoint.gateway.audit;

/** Where audit entries go. Stdout today; an append-only store would plug in here. */
public interface AuditSink {

    void write(AuditEntry entry);
}
