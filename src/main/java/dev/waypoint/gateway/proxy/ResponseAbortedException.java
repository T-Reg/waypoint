package dev.waypoint.gateway.proxy;

/**
 * The upstream body failed after the response was committed. Propagated to the servlet container so it
 * closes the connection instead of terminating the body cleanly, which would pass a truncated body as complete.
 */
public final class ResponseAbortedException extends RuntimeException {

    public ResponseAbortedException(String code) {
        super(code, null, false, false);
    }
}
