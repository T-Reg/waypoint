package dev.waypoint.gateway.auth;

public final class TokenException extends Exception {

    public enum Reason { INVALID, EXPIRED }

    private final Reason reason;

    public TokenException(Reason reason, String message) {
        super(message, null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
