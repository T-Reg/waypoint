package dev.waypoint.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class HeaderPolicyTest {

    @Test
    void stripsHopByHopCredentialsAndSpoofableHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("Connection", "keep-alive, X-Custom-Hop, X-Gateway-Identity");
        request.addHeader("Keep-Alive", "timeout=5");
        request.addHeader("TE", "trailers");
        request.addHeader("Trailer", "x");
        request.addHeader("Transfer-Encoding", "chunked");
        request.addHeader("Upgrade", "h2c");
        request.addHeader("Proxy-Authorization", "Basic x");
        request.addHeader("Proxy-Connection", "keep-alive");
        request.addHeader("HTTP2-Settings", "x");
        request.addHeader("X-Custom-Hop", "drop me");
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("X-Gateway-Identity", "forged");
        request.addHeader("X-Gateway-Tenant-Id", "globex");
        request.addHeader("Forwarded", "for=1.2.3.4");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("X-Forwarded-Host", "evil");
        request.addHeader("X-Real-IP", "1.2.3.4");
        request.addHeader("X-Request-Id", "client-chosen");
        request.addHeader("Host", "gateway.example");
        request.addHeader("Content-Length", "3");
        request.addHeader("Expect", "100-continue");
        request.addHeader("Accept", "application/json");
        request.addHeader("X-Custom", "a");
        request.addHeader("X-Custom", "b");
        request.addHeader("Cookie", "session=1");

        List<Map.Entry<String, String>> forwarded = HeaderPolicy.requestHeaders(request);
        assertThat(forwarded).extracting(Map.Entry::getKey).map(String::toLowerCase)
                .containsExactlyInAnyOrder("accept", "x-custom", "x-custom", "cookie");
        assertThat(forwarded).contains(Map.entry("X-Custom", "a"), Map.entry("X-Custom", "b"));
    }

    @Test
    void responseDropsHopByHopAndConnectionNamedHeaders() {
        HttpHeaders upstream = HttpHeaders.of(Map.of(
                "connection", List.of("close, x-internal"),
                "x-internal", List.of("secret"),
                "transfer-encoding", List.of("chunked"),
                "keep-alive", List.of("timeout=5"),
                ":status", List.of("200"),
                "content-type", List.of("text/plain"),
                "set-cookie", List.of("a=1", "b=2"),
                "content-length", List.of("5")), (a, b) -> true);
        List<Map.Entry<String, String>> headers = HeaderPolicy.responseHeaders(upstream);
        assertThat(headers).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("content-type", "set-cookie", "set-cookie", "content-length");
    }

    @Test
    void connectionTokensAreCaseInsensitive() {
        assertThat(HeaderPolicy.connectionTokens(List.of("Keep-Alive, , X-A", "x-b"))).containsExactlyInAnyOrder("keep-alive", "x-a", "x-b");
        assertThat(HeaderPolicy.isHopByHop("proxy-anything")).isTrue();
        assertThat(HeaderPolicy.isHopByHop("accept")).isFalse();
    }
}
