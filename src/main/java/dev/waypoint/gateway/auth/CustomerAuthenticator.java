package dev.waypoint.gateway.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import dev.waypoint.gateway.store.ConfigSnapshot;
import dev.waypoint.gateway.tenant.Tenant;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Authenticates customer tokens and resolves the tenant from the verified {@code tid} claim. */
public class CustomerAuthenticator {

    public static final String MISSING = "auth.missing";
    public static final String INVALID = "auth.invalid";
    public static final String EXPIRED = "auth.expired";
    public static final String UNKNOWN_TENANT = "auth.unknown_tenant";

    private final Ed25519JwtVerifier verifier;

    public CustomerAuthenticator(Ed25519JwtVerifier verifier) {
        this.verifier = verifier;
    }

    public AuthResult authenticate(Enumeration<String> authorizationHeaders, ConfigSnapshot snapshot) {
        BearerTokens.Extracted extracted = BearerTokens.extract(authorizationHeaders);
        if (extracted instanceof BearerTokens.Extracted.Missing) {
            return new AuthResult.Failure(MISSING, "a bearer token is required");
        }
        if (extracted instanceof BearerTokens.Extracted.Malformed malformed) {
            return new AuthResult.Failure(INVALID, malformed.detail());
        }
        String token = ((BearerTokens.Extracted.Token) extracted).value();
        JWTClaimsSet claims;
        try {
            claims = verifier.verify(token);
        } catch (TokenException e) {
            return e.reason() == TokenException.Reason.EXPIRED
                    ? new AuthResult.Failure(EXPIRED, "token expired")
                    : new AuthResult.Failure(INVALID, "token is invalid");
        }
        Optional<String> tenantId = BearerTokens.stringClaim(claims.getClaim("tid"));
        if (tenantId.isEmpty()) {
            return new AuthResult.Failure(INVALID, "token is invalid");
        }
        Object scopeClaim = claims.getClaim("scope");
        if (scopeClaim != null && !(scopeClaim instanceof String)) {
            return new AuthResult.Failure(INVALID, "token is invalid");
        }
        Tenant tenant = snapshot.tenants().get(tenantId.get());
        if (tenant == null || tenant.disabled()) {
            return new AuthResult.Failure(UNKNOWN_TENANT, "tenant is unknown or disabled");
        }
        return new AuthResult.Authenticated(
                new TenantIdentity(tenant.id(), claims.getSubject(), parseScopes((String) scopeClaim)), tenant);
    }

    static Set<String> parseScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(scope.strip().split(" +")).collect(Collectors.toUnmodifiableSet());
    }
}
