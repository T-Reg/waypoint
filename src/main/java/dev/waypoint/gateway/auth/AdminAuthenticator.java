package dev.waypoint.gateway.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.Enumeration;

/** Admin tokens: separate key, {@code iss}, {@code aud=gateway-admin} and {@code typ=admin+jwt}. */
public class AdminAuthenticator {

    public sealed interface Result {
        record Authenticated(AdminIdentity identity) implements Result {}
        record Failure(String detail) implements Result {}
    }

    private final Ed25519JwtVerifier verifier;

    public AdminAuthenticator(Ed25519JwtVerifier verifier) {
        this.verifier = verifier;
    }

    public Result authenticate(Enumeration<String> authorizationHeaders) {
        BearerTokens.Extracted extracted = BearerTokens.extract(authorizationHeaders);
        if (!(extracted instanceof BearerTokens.Extracted.Token token)) {
            return new Result.Failure("an admin bearer token is required");
        }
        try {
            JWTClaimsSet claims = verifier.verify(token.value());
            Object scope = claims.getClaim("scope");
            if (scope != null && !(scope instanceof String)) {
                return new Result.Failure("admin token is invalid");
            }
            return new Result.Authenticated(new AdminIdentity(claims.getSubject(), CustomerAuthenticator.parseScopes((String) scope)));
        } catch (TokenException e) {
            return new Result.Failure("admin token is invalid or expired");
        }
    }
}
