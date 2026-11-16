package dev.waypoint.gateway.auth;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

/** Loads public verification keys from an inline JWK / JWK set or a file. Private parameters are dropped. */
public final class JwkLoader {

    private JwkLoader() {}

    public static JWKSet load(String inlineJson, String file, String what) {
        String json = inlineJson;
        if (json == null || json.isBlank()) {
            if (file == null || file.isBlank()) {
                throw new IllegalStateException(what + ": configure public-jwk or public-jwk-file");
            }
            try {
                json = Files.readString(Path.of(file));
            } catch (IOException e) {
                throw new IllegalStateException(what + ": cannot read " + file, e);
            }
        }
        return parse(json, what);
    }

    public static JWKSet parse(String json, String what) {
        try {
            Map<String, Object> object = com.nimbusds.jose.util.JSONObjectUtils.parse(json);
            JWKSet set = object.containsKey("keys") ? JWKSet.parse(object) : new JWKSet(List.of(JWK.parse(object)));
            return set.toPublicJWKSet();
        } catch (ParseException e) {
            throw new IllegalStateException(what + ": not a valid JWK or JWK set", e);
        }
    }
}
