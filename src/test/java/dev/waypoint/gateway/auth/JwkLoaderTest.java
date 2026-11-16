package dev.waypoint.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import dev.waypoint.gateway.config.GatewayProperties;
import dev.waypoint.gateway.support.TestTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JwkLoaderTest {

    @Test
    void loadsInlineSingleKeyAndDropsPrivatePart() {
        JWKSet set = JwkLoader.load(TestTokens.ISSUER_KEY.toJSONString(), null, "test");
        assertThat(set.getKeys()).hasSize(1);
        assertThat(set.getKeys().get(0).isPrivate()).isFalse();
    }

    @Test
    void loadsKeySetFromFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keys.json");
        Files.writeString(file, new JWKSet(TestTokens.ISSUER_KEY.toPublicJWK()).toString());
        assertThat(JwkLoader.load("", file.toString(), "test").getKeys()).hasSize(1);
    }

    @Test
    void failsClearly() {
        assertThatThrownBy(() -> JwkLoader.load(null, null, "x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwkLoader.load(null, "/does/not/exist", "x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwkLoader.load("{not json", null, "x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adminAndCustomerTrustMustNotOverlap() {
        JWKSet customer = new JWKSet(TestTokens.ISSUER_KEY.toPublicJWK());
        JWKSet admin = new JWKSet(TestTokens.ADMIN_KEY.toPublicJWK());
        GatewayProperties.TokenTrust customerTrust = new GatewayProperties.TokenTrust("ci", "gateway", null, null);
        GatewayProperties.TokenTrust adminTrust = new GatewayProperties.TokenTrust("ai", "gateway-admin", null, null);
        AuthConfig.requireSeparateTrust(admin, customer, adminTrust, customerTrust);
        assertThatThrownBy(() -> AuthConfig.requireSeparateTrust(customer, customer, adminTrust, customerTrust))
                .hasMessageContaining("different signing keys");
        assertThatThrownBy(() -> AuthConfig.requireSeparateTrust(admin, customer,
                new GatewayProperties.TokenTrust("ai", "gateway", null, null), customerTrust)).hasMessageContaining("audiences");
        assertThatThrownBy(() -> AuthConfig.requireSeparateTrust(admin, customer,
                new GatewayProperties.TokenTrust("ci", "gateway-admin", null, null), customerTrust)).hasMessageContaining("issuers");
    }
}
