package dev.waypoint.gateway.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import dev.waypoint.gateway.config.GatewayProperties;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuthConfig {

    @Bean
    CustomerAuthenticator customerAuthenticator(GatewayProperties properties, Clock clock) {
        GatewayProperties.TokenTrust trust = properties.auth().customer();
        return new CustomerAuthenticator(new Ed25519JwtVerifier(
                JwkLoader.load(trust.publicJwk(), trust.publicJwkFile(), "gateway.auth.customer"),
                trust.issuer(), trust.audience(), TokenTypes.CUSTOMER, properties.auth().clockSkew(), clock));
    }

    @Bean
    AdminAuthenticator adminAuthenticator(GatewayProperties properties, Clock clock) {
        GatewayProperties.TokenTrust trust = properties.auth().admin();
        GatewayProperties.TokenTrust customer = properties.auth().customer();
        JWKSet adminKeys = JwkLoader.load(trust.publicJwk(), trust.publicJwkFile(), "gateway.auth.admin");
        requireSeparateTrust(adminKeys, JwkLoader.load(customer.publicJwk(), customer.publicJwkFile(), "gateway.auth.customer"),
                trust, customer);
        return new AdminAuthenticator(new Ed25519JwtVerifier(adminKeys,
                trust.issuer(), trust.audience(), TokenTypes.ADMIN, properties.auth().clockSkew(), clock));
    }

    /** Stealing the customer-issuer key must never grant admin, so the two trust roots can't overlap. */
    static void requireSeparateTrust(JWKSet adminKeys, JWKSet customerKeys,
                                     GatewayProperties.TokenTrust admin, GatewayProperties.TokenTrust customer) {
        Set<String> customerThumbprints = thumbprints(customerKeys);
        if (thumbprints(adminKeys).stream().anyMatch(customerThumbprints::contains)) {
            throw new IllegalStateException("admin and customer tokens must use different signing keys");
        }
        if (Objects.equals(admin.audience(), customer.audience()) || Objects.equals(admin.issuer(), customer.issuer())) {
            throw new IllegalStateException("admin and customer tokens must use different issuers and audiences");
        }
    }

    private static Set<String> thumbprints(JWKSet keys) {
        return keys.getKeys().stream().map(key -> {
            try {
                return key.computeThumbprint().toString();
            } catch (JOSEException e) {
                throw new IllegalStateException("cannot compute key thumbprint", e);
            }
        }).collect(Collectors.toSet());
    }
}
