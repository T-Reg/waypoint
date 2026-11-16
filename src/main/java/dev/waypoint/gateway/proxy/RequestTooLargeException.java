package dev.waypoint.gateway.proxy;

import java.io.IOException;

public final class RequestTooLargeException extends IOException {

    public RequestTooLargeException() {
        super("request body exceeds the configured limit");
    }
}
