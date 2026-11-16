package dev.waypoint.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import dev.waypoint.gateway.support.AuditCapture;
import dev.waypoint.gateway.support.MutableClock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditTest {

    private static final byte[] KEY_A = "0123456789abcdef0123456789abcdef".getBytes();
    private static final byte[] KEY_B = "fedcba9876543210fedcba9876543210".getBytes();

    @Test
    void hmacIsStablePerKeyAndChangesWithRotation() {
        PathHasher a = new PathHasher(KEY_A);
        assertThat(a.hash("/cities/chicago")).isEqualTo(new PathHasher(KEY_A).hash("/cities/chicago"));
        assertThat(a.hash("/cities/chicago")).isNotEqualTo(a.hash("/cities/boston"));
        assertThat(a.hash("/cities/chicago")).isNotEqualTo(new PathHasher(KEY_B).hash("/cities/chicago"));
        assertThat(a.hash("/cities/chicago")).doesNotContain("chicago");
    }

    @Test
    void hmacKeyMustBeStrong() {
        assertThatThrownBy(() -> new PathHasher(new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PathHasher(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathHasher.fromBase64(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PathHasher.fromBase64(null)).isInstanceOf(IllegalStateException.class);
        assertThat(PathHasher.fromBase64(Base64.getEncoder().encodeToString(KEY_A)).hash("/x")).isEqualTo(new PathHasher(KEY_A).hash("/x"));
    }

    @Test
    void jsonSinkWritesOneEscapedLine() throws Exception {
        try (AuditCapture capture = new AuditCapture()) {
            new JsonAuditSink(new ObjectMapper()).write(entry("line\nbreak\"}", null));
            List<String> lines = capture.lines();
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).doesNotContain("\n");
            JsonNode json = new ObjectMapper().readTree(lines.get(0));
            assertThat(json.path("tenant_id").asString()).isEqualTo("line\nbreak\"}");
            assertThat(json.has("path")).isFalse();
            assertThat(json.has("deny_code")).isTrue();
            assertThat(json.path("decision").asString()).isEqualTo("DENY");
        }
    }

    @Test
    void rawPathAppearsOnlyWhenSet() throws Exception {
        try (AuditCapture capture = new AuditCapture()) {
            new JsonAuditSink(new ObjectMapper()).write(entry("acme", "/cities/chicago"));
            assertThat(new ObjectMapper().readTree(capture.lines().get(0)).path("path").asString()).isEqualTo("/cities/chicago");
        }
    }

    @Test
    void fieldsKeepDeclarationOrderEvenWhenMapperSortsAlphabetically() throws Exception {
        try (AuditCapture capture = new AuditCapture()) {
            new JsonAuditSink(JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build())
                    .write(entry("acme", "/cities/chicago"));
            JsonNode json = new ObjectMapper().readTree(capture.lines().get(0));
            assertThat(json.propertyNames()).containsExactly("ts", "request_id", "listener", "tenant_id", "sub", "method",
                    "path_prefix", "path_hmac", "path", "route_id", "upstream", "decision", "deny_code", "error_code",
                    "status", "upstream_status", "latency_ms", "client_ip");
        }
    }

    @Test
    void recorderSwallowsSinkFailures() {
        List<AuditEntry> written = new ArrayList<>();
        AuditRecorder recorder = new AuditRecorder(e -> {
            written.add(e);
            throw new IllegalStateException("disk full");
        }, new PathHasher(KEY_A), MutableClock.at("2026-01-01T00:00:00Z"));
        recorder.recordAdmin(new AuditRecorder.AdminRequest("r", "PUT", "/admin/routes/x", "alice", 200, null, System.nanoTime(), "1.1.1.1"));
        recorder.recordAdmin(new AuditRecorder.AdminRequest("r2", "PUT", "/admin/routes/x", null, 401, "admin.unauthorized", System.nanoTime(), "1.1.1.1"));
        assertThat(written).extracting(AuditEntry::decision).containsExactly(AuditEntry.Decision.ALLOW, AuditEntry.Decision.DENY);
        assertThat(written.get(0).ts()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(written.get(0).path()).isEqualTo("/admin/routes/x");
    }

    private static AuditEntry entry(String tenant, String path) {
        return new AuditEntry("2026-01-01T00:00:00Z", "r", "public", tenant, "web", "GET", "/cities", "h", path, "cities",
                "http://up:80", AuditEntry.Decision.DENY, "policy.path_denied", null, 403, null, 3, "1.1.1.1");
    }
}
