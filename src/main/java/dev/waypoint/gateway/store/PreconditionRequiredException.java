package dev.waypoint.gateway.store;

public class PreconditionRequiredException extends RuntimeException {

    public PreconditionRequiredException() {
        super("If-Match with the current version is required to change an existing entry");
    }
}
