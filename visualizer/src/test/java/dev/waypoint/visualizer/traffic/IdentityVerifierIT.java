package dev.waypoint.visualizer.traffic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.jwk.OctetKeyPair;
import dev.waypoint.visualizer.config.VisualizerConfig;
import dev.waypoint.visualizer.support.MutableClock;
import dev.waypoint.visualizer.support.TestSupport;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Key rotation as an upstream sees it: refetch the JWKS on an unknown kid, but not more than every 5s. */
class IdentityVerifierIT {

    private static final String JWKS = "/.well-known/jwks.json";

    private final MutableClock clock = new MutableClock(TestSupport.NOW);
    private WireMockServer gateway;
    private IdentityVerifier verifier;

    @BeforeEach
    void start() {
        gateway = new WireMockServer(options().dynamicPort());
        gateway.start();
        verifier = new IdentityVerifier(new VisualizerConfig().httpClient(),
                TestSupport.properties("http://gw", "http://up", gateway.baseUrl() + JWKS), clock);
    }

    @AfterEach
    void stop() {
        gateway.stop();
    }

    private void publish(OctetKeyPair... keys) {
        gateway.stubFor(get(JWKS).willReturn(aResponse().withBody(TestSupport.jwks(keys))));
    }

    @Test
    void picksUpRotatedKeysAfterTheRefetchInterval() {
        OctetKeyPair rotated = TestSupport.generate("gateway-next");
        publish(TestSupport.GATEWAY_KEY);
        assertThat(verifier.verify(TestSupport.identityToken(TestSupport.GATEWAY_KEY, "acme")).verified()).isTrue();

        publish(TestSupport.GATEWAY_KEY, rotated);
        String newToken = TestSupport.identityToken(rotated, "acme");
        clock.advance(Duration.ofSeconds(2));
        assertThat(verifier.verify(newToken).error()).isEqualTo("kid not in gateway JWKS");

        clock.advance(Duration.ofSeconds(4));
        assertThat(verifier.verify(newToken).verified()).isTrue();
        gateway.verify(2, getRequestedFor(urlEqualTo(JWKS)));
    }

    @Test
    void knownKidsNeverTriggerAFetch() {
        publish(TestSupport.GATEWAY_KEY);
        String token = TestSupport.identityToken(TestSupport.GATEWAY_KEY, "acme");

        verifier.verify(token);
        clock.advance(Duration.ofMinutes(1));
        verifier.verify(token);

        gateway.verify(1, getRequestedFor(urlEqualTo(JWKS)));
    }

    @Test
    void invalidJwksIsReported() {
        gateway.stubFor(get(JWKS).willReturn(aResponse().withBody("not json")));

        assertThat(verifier.verify(TestSupport.identityToken(TestSupport.GATEWAY_KEY, "acme")).error())
                .startsWith("JWKS unavailable");
    }
}
