package dev.waypoint.visualizer.token;

/** Ways to break a customer token on purpose, each one aimed at a specific gateway check. */
public enum TokenFault {
    NONE("Valid token"),
    MISSING("No Authorization header"),
    EXPIRED("Expired 5 minutes ago"),
    NOT_YET_VALID("nbf 5 minutes in the future"),
    TAMPERED("Payload edited after signing (tid swapped)"),
    WRONG_AUDIENCE("aud=gateway-admin instead of gateway"),
    ALG_NONE("Unsigned, alg=none"),
    ALG_HS256("HS256 keyed with the issuer's public key (alg confusion)");

    private final String description;

    TokenFault(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
