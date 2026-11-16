package dev.waypoint.gateway.audit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyed HMAC-SHA256 of the normalized path, so identical requests correlate without storing the
 * path. Pseudonymization, not anonymization: the key holder can brute-force low-variety paths.
 */
public final class PathHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;

    private final SecretKeySpec key;

    public PathHasher(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("gateway.audit.path-hmac-key must be at least 32 bytes (base64)");
        }
        this.key = new SecretKeySpec(keyBytes.clone(), ALGORITHM);
    }

    public static PathHasher fromBase64(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("gateway.audit.path-hmac-key is required");
        }
        return new PathHasher(Base64.getDecoder().decode(base64Key.strip()));
    }

    public String hash(String path) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(path.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
