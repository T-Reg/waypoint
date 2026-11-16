package dev.waypoint.gateway.path;

public final class InvalidPathException extends Exception {

    public InvalidPathException(String reason) {
        super(reason, null, false, false);
    }
}
