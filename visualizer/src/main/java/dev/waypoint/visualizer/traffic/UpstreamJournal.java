package dev.waypoint.visualizer.traffic;

import dev.waypoint.visualizer.config.VisualizerProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asks the stub upstream (WireMock) whether it received a request, by the X-Request-Id the gateway
 * injects. Proves that rejected requests never reach the upstream and shows the headers it saw.
 */
@Component
public class UpstreamJournal {

    private static final Logger log = LoggerFactory.getLogger(UpstreamJournal.class);
    private static final String IDENTITY_HEADER = "X-Gateway-Identity";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI findUri;
    private final IdentityVerifier identities;

    public UpstreamJournal(HttpClient http, ObjectMapper mapper, VisualizerProperties properties, IdentityVerifier identities) {
        this.http = http;
        this.mapper = mapper;
        this.findUri = URI.create(properties.upstreamAdminUrl() + "/requests/find");
        this.identities = identities;
    }

    public RequestResult.Upstream lookup(String requestId) {
        if (requestId == null) {
            return RequestResult.Upstream.notReceived();
        }
        try {
            String query = mapper.writeValueAsString(Map.of("headers", Map.of("X-Request-Id", Map.of("equalTo", requestId))));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(findUri)
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(query))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return RequestResult.Upstream.unchecked();
            }
            JsonNode requests = mapper.readTree(response.body()).path("requests");
            if (requests.isEmpty()) {
                return RequestResult.Upstream.notReceived();
            }
            return toUpstream(requests.get(0));
        } catch (IOException | JacksonException e) {
            log.debug("upstream journal unavailable: {}", e.toString());
            return RequestResult.Upstream.unchecked();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RequestResult.Upstream.unchecked();
        }
    }

    private RequestResult.Upstream toUpstream(JsonNode logged) {
        Map<String, String> headers = new LinkedHashMap<>();
        logged.path("headers").properties().forEach(entry -> headers.put(entry.getKey(), headerValue(entry.getValue())));
        RequestResult.Identity identity = null;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase(IDENTITY_HEADER)) {
                identity = identities.verify(header.getValue());
                header.setValue(withoutSignature(header.getValue()));
            }
        }
        return new RequestResult.Upstream(true, true, logged.path("url").asString(null), headers, identity);
    }

    /** The page shows the claims but never a replayable token. */
    static String withoutSignature(String token) {
        int lastDot = token.lastIndexOf('.');
        return lastDot < 0 ? "<redacted>" : token.substring(0, lastDot) + ".<signature redacted>";
    }

    private static String headerValue(JsonNode value) {
        if (value.isArray()) {
            StringBuilder joined = new StringBuilder();
            value.forEach(v -> joined.append(joined.isEmpty() ? "" : ", ").append(v.asString()));
            return joined.toString();
        }
        return value.asString();
    }
}
