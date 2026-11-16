package dev.waypoint.gateway.identity;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetKeyPair;
import java.text.ParseException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code gateway_keys}: public keys only, so every instance can publish every live key. */
public class KeyRegistry {

    public record LiveKeys(List<JWK> keys, int liveInstances) {}

    public static final String NOTIFY_PREFIX = "key:";

    private final JdbcClient jdbc;
    private final String channel;

    public KeyRegistry(JdbcClient jdbc, String channel) {
        this.jdbc = jdbc;
        this.channel = channel;
    }

    /** Inserts the public key and notifies peers in one statement, so the NOTIFY only fires once the row is committed. */
    public void register(OctetKeyPair key, String instanceId, Instant now) {
        jdbc.sql("""
                WITH inserted AS (
                    INSERT INTO gateway_keys (kid, public_jwk, instance_id, created_at, last_heartbeat)
                    VALUES (:kid, :jwk, :instance, :now, :now) RETURNING kid)
                SELECT pg_notify(:channel, :prefix || kid) FROM inserted""")
                .param("kid", key.getKeyID()).param("jwk", key.toPublicJWK().toJSONString())
                .param("instance", instanceId).param("now", ts(now))
                .param("channel", channel).param("prefix", NOTIFY_PREFIX).query().listOfRows();
    }

    public void heartbeat(String instanceId, Instant now) {
        jdbc.sql("UPDATE gateway_keys SET last_heartbeat = :now WHERE instance_id = :instance")
                .param("now", ts(now)).param("instance", instanceId).update();
    }

    /** Sets the key's retirement time; it can move later (a re-rotation during the grace) but never earlier. */
    public void retire(String kid, Instant at) {
        jdbc.sql("UPDATE gateway_keys SET retired_at = :at WHERE kid = :kid AND (retired_at IS NULL OR retired_at < :at)")
                .param("at", ts(at)).param("kid", kid).update();
    }

    public void retireInstance(String instanceId, Instant now) {
        jdbc.sql("UPDATE gateway_keys SET retired_at = :now WHERE instance_id = :instance AND retired_at IS NULL")
                .param("now", ts(now)).param("instance", instanceId).update();
    }

    /** Keys not retired past {@code retiredCutoff} whose instance heartbeated after {@code staleCutoff}. */
    public LiveKeys liveKeys(Instant retiredCutoff, Instant staleCutoff, Instant liveInstanceCutoff) {
        List<JWK> keys = jdbc.sql("SELECT public_jwk FROM gateway_keys WHERE (retired_at IS NULL OR retired_at > :retired) AND last_heartbeat > :stale")
                .param("retired", ts(retiredCutoff)).param("stale", ts(staleCutoff))
                .query((rs, i) -> parse(rs.getString(1))).list().stream().filter(Objects::nonNull).toList();
        int instances = jdbc.sql("SELECT count(DISTINCT instance_id) FROM gateway_keys WHERE retired_at IS NULL AND last_heartbeat > :live")
                .param("live", ts(liveInstanceCutoff)).query(Integer.class).single();
        return new LiveKeys(keys, instances);
    }

    public void deleteOlderThan(Instant cutoff) {
        jdbc.sql("DELETE FROM gateway_keys WHERE retired_at < :cutoff OR last_heartbeat < :cutoff").param("cutoff", ts(cutoff)).update();
    }

    private static JWK parse(String json) {
        try {
            return JWK.parse(json).toPublicJWK();
        } catch (ParseException e) {
            return null;
        }
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
