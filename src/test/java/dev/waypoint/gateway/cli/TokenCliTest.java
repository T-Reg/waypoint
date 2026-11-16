package dev.waypoint.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import dev.waypoint.gateway.auth.Ed25519JwtVerifier;
import dev.waypoint.gateway.auth.JwkLoader;
import dev.waypoint.gateway.auth.TokenTypes;
import dev.waypoint.gateway.support.MutableClock;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenCliTest {

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final TokenCli cli = new TokenCli(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), clock);

    @TempDir
    Path dir;

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8).strip();
    }

    @Test
    void keygenIssueAndVerifyCustomerToken() throws Exception {
        assertThat(cli.run(new String[] {"keygen", "--out-dir", dir.toString(), "--name", "issuer"})).isZero();
        Path privateKey = dir.resolve("issuer.private.jwk.json");
        assertThat(Files.getPosixFilePermissions(privateKey)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readString(dir.resolve("issuer.public.jwk.json"))).doesNotContain("\"d\"");
        out.reset();
        assertThat(cli.run(new String[] {"issue", "--key", privateKey.toString(), "--tenant", "acme", "--sub", "web-app",
                "--scope", "weather:read", "--ttl", "15m"})).isZero();
        JWKSet keys = JwkLoader.load(null, dir.resolve("issuer.public.jwk.json").toString(), "test");
        JWTClaimsSet claims = new Ed25519JwtVerifier(keys, "waypoint-issuer", "gateway", TokenTypes.CUSTOMER, Duration.ZERO, clock).verify(stdout());
        assertThat(claims.getClaim("tid")).isEqualTo("acme");
        assertThat(claims.getClaim("scope")).isEqualTo("weather:read");
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void issueAdminToken() throws Exception {
        cli.run(new String[] {"keygen", "--out-dir", dir.toString(), "--name", "admin"});
        out.reset();
        assertThat(cli.run(new String[] {"issue-admin", "--key", dir.resolve("admin.private.jwk.json").toString(), "--sub", "alice",
                "--scope", "admin:routes"})).isZero();
        JWKSet keys = JwkLoader.load(null, dir.resolve("admin.public.jwk.json").toString(), "test");
        JWTClaimsSet claims = new Ed25519JwtVerifier(keys, "waypoint-admin-issuer", "gateway-admin", TokenTypes.ADMIN, Duration.ZERO, clock).verify(stdout());
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.getClaim("tid")).isNull();
    }

    @Test
    void rejectsBadInput() throws Exception {
        assertThat(cli.run(new String[0])).isEqualTo(2);
        assertThat(cli.run(new String[] {"bogus"})).isEqualTo(2);
        assertThat(cli.run(new String[] {"keygen", "--out-dir"})).isEqualTo(2);
        assertThat(cli.run(new String[] {"keygen", "--out-dir", dir.toString(), "--name", "Bad Name"})).isEqualTo(2);
        cli.run(new String[] {"keygen", "--out-dir", dir.toString(), "--name", "k"});
        assertThat(cli.run(new String[] {"keygen", "--out-dir", dir.toString(), "--name", "k"})).as("no overwrite").isEqualTo(2);
        String key = dir.resolve("k.private.jwk.json").toString();
        assertThat(cli.run(new String[] {"issue", "--key", key, "--sub", "x"})).as("tenant required").isEqualTo(2);
        assertThat(cli.run(new String[] {"issue", "--key", key, "--tenant", "a", "--sub", "x", "--ttl", "25h"})).isEqualTo(2);
        assertThat(cli.run(new String[] {"issue-admin", "--key", key, "--sub", "x", "--scope", "admin:keys", "--ttl", "2h"})).isEqualTo(2);
        assertThat(cli.run(new String[] {"issue-admin", "--key", key, "--sub", "x"})).as("admin scope required").isEqualTo(2);
        assertThat(cli.run(new String[] {"issue", "--key", dir.resolve("k.public.jwk.json").toString(), "--tenant", "a", "--sub", "x"}))
                .as("public key can't sign").isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Usage:");
    }

    @Test
    void parsesTtl() {
        assertThat(TokenCli.parseTtl("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(TokenCli.parseTtl("2h")).isEqualTo(Duration.ofHours(2));
        assertThatThrownBy(() -> TokenCli.parseTtl("0m")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TokenCli.parseTtl("15 minutes")).isInstanceOf(IllegalArgumentException.class);
    }
}
