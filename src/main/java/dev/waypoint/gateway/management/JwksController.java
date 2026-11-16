package dev.waypoint.gateway.management;

import dev.waypoint.gateway.identity.GatewayKeyStore;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public identity-token verification keys (RFC 7517). Served on the management listener only. */
@RestController
public class JwksController {

    public static final MediaType JWK_SET = MediaType.parseMediaType("application/jwk-set+json");

    private final GatewayKeyStore keyStore;

    public JwksController(GatewayKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<String> jwks() {
        return ResponseEntity.ok()
                .contentType(JWK_SET)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)))
                .body(keyStore.publicJwks().toString(true));
    }
}
