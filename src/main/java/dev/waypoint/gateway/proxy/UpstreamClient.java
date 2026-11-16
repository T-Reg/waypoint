package dev.waypoint.gateway.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Seam for a per-upstream circuit-breaker decorator (design.md §10). */
public interface UpstreamClient {

    HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
}
