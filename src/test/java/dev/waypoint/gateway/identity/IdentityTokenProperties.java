package dev.waypoint.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import dev.waypoint.gateway.auth.Ed25519JwtVerifier;
import dev.waypoint.gateway.auth.TenantIdentity;
import dev.waypoint.gateway.auth.TokenException;
import dev.waypoint.gateway.auth.TokenTypes;
import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.routing.Upstream;
import dev.waypoint.gateway.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class IdentityTokenProperties {

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
    private final GatewayKeyStore keys = new GatewayKeyStore(clock, Duration.ofSeconds(90));
    private final IdentityTokenMinter minter = new IdentityTokenMinter(keys, "waypoint-gateway", Duration.ofSeconds(60), clock);
    private final Route route = new Route("r", "/r", List.of(new Upstream("http://127.0.0.1:1", 100)), false,
            Map.of("*", List.of()), List.of("*:*"), true, "aud-r", 1000, true);
    private final Ed25519JwtVerifier verifier;

    IdentityTokenProperties() {
        keys.activate(GatewayKeyStore.generate());
        verifier = new Ed25519JwtVerifier(keys.publicJwks(), "waypoint-gateway", "aud-r", TokenTypes.IDENTITY, Duration.ZERO, clock);
    }

    @Provide
    Arbitrary<String> ids() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(40).filter(s -> !s.isBlank());
    }

    @Provide
    Arbitrary<Set<String>> scopes() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(6)
                .map(s -> "*:" + s).set().ofMaxSize(5);
    }

    @Property(tries = 300)
    void mintThenVerifyRoundTripsClaims(@ForAll("ids") String tid, @ForAll("ids") String sub, @ForAll("scopes") Set<String> scopes)
            throws TokenException {
        JWTClaimsSet claims = verifier.verify(minter.mint(new TenantIdentity(tid, sub, scopes), route, "rid"));
        assertThat(claims.getClaim("tid")).isEqualTo(tid);
        assertThat(claims.getSubject()).isEqualTo(sub);
        Object scope = claims.getClaim("scope");
        Set<String> roundTripped = scope == null ? Set.of() : new TreeSet<>(Arrays.asList(((String) scope).split(" ")));
        assertThat(roundTripped).isEqualTo(new TreeSet<>(scopes));
    }

    @Property(tries = 300)
    void flippingAnyByteBreaksVerification(@ForAll @IntRange(min = 0, max = 10_000) int position, @ForAll @IntRange(min = 1, max = 255) int xor) {
        byte[] token = minter.mint(new TenantIdentity("acme", "web", Set.of()), route, "rid").getBytes(StandardCharsets.US_ASCII);
        int index = position % token.length;
        token[index] = (byte) (token[index] ^ xor);
        String tampered = new String(token, StandardCharsets.ISO_8859_1);
        try {
            verifier.verify(tampered);
            // Base64url decoders may ignore unused low bits of the final signature char; the decoded bytes must still be identical.
            assertThat(index).isEqualTo(token.length - 1);
        } catch (TokenException expected) {
            // rejected
        }
    }
}
