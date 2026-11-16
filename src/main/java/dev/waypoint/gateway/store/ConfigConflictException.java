package dev.waypoint.gateway.store;

public class ConfigConflictException extends RuntimeException {

    public ConfigConflictException(String message) {
        super(message);
    }
}
