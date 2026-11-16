package dev.waypoint.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import dev.waypoint.gateway.support.MutableClock;
import dev.waypoint.gateway.support.TestTokens;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminAuthenticatorTest {

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
    private final AdminAuthenticator authenticator = new AdminAuthenticator(new Ed25519JwtVerifier(
            new JWKSet(TestTokens.ADMIN_KEY.toPublicJWK()), "waypoint-admin-issuer", "gateway-admin", TokenTypes.ADMIN, Duration.ofSeconds(30), clock));

    private AdminAuthenticator.Result auth(String header) {
        return authenticator.authenticate(header == null ? null : Collections.enumeration(List.of(header)));
    }

    @Test
    void acceptsAdminToken() {
        assertThat(auth("Bearer " + TestTokens.admin().clock(clock).scope("admin:routes admin:keys").sign()))
                .isInstanceOfSatisfying(AdminAuthenticator.Result.Authenticated.class, ok -> {
                    assertThat(ok.identity().subject()).isEqualTo("alice");
                    assertThat(ok.identity().scopes()).containsExactlyInAnyOrder("admin:routes", "admin:keys");
                });
    }

    @Test
    void acceptsAdminTokenWithoutScope() {
        assertThat(auth("Bearer " + TestTokens.admin().clock(clock).sign())).isInstanceOf(AdminAuthenticator.Result.Authenticated.class);
    }

    @Test
    void rejectsMissingMalformedAndCustomerTokens() {
        assertThat(auth(null)).isInstanceOf(AdminAuthenticator.Result.Failure.class);
        assertThat(auth("Basic abc")).isInstanceOf(AdminAuthenticator.Result.Failure.class);
        assertThat(auth("Bearer " + TestTokens.customer().clock(clock).sign())).isInstanceOf(AdminAuthenticator.Result.Failure.class);
        // Admin key but customer audience/type: still rejected.
        assertThat(auth("Bearer " + TestTokens.admin().clock(clock).audience("gateway").sign())).isInstanceOf(AdminAuthenticator.Result.Failure.class);
        assertThat(auth("Bearer " + TestTokens.admin().clock(clock).type(TokenTypes.CUSTOMER).sign())).isInstanceOf(AdminAuthenticator.Result.Failure.class);
    }

    @Test
    void rejectsNonStringScope() {
        assertThat(auth("Bearer " + TestTokens.admin().clock(clock).scope(List.of("admin:routes")).sign()))
                .isInstanceOf(AdminAuthenticator.Result.Failure.class);
    }

    @Test
    void rejectsExpiredAdminToken() {
        String token = TestTokens.admin().clock(clock).ttl(Duration.ofMinutes(10)).sign();
        clock.advance(Duration.ofMinutes(11));
        assertThat(auth("Bearer " + token)).isInstanceOf(AdminAuthenticator.Result.Failure.class);
    }
}
