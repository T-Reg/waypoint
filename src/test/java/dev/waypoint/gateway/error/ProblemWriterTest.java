package dev.waypoint.gateway.error;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class ProblemWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProblemWriter writer = new ProblemWriter(mapper);

    @Test
    void writesRfc9457ProblemWithCodeAndHeaders() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        writer.write(response, Rejection.of(429, "policy.quota_exceeded", "slow down").withHeader("Retry-After", "7"), "rid-1");
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("Retry-After")).isEqualTo("7");
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertThat(body.path("type").asString()).isEqualTo("about:blank");
        assertThat(body.path("title").asString()).isEqualTo("Too Many Requests");
        assertThat(body.path("status").asInt()).isEqualTo(429);
        assertThat(body.path("code").asString()).isEqualTo("policy.quota_exceeded");
        assertThat(body.path("request_id").asString()).isEqualTo("rid-1");
    }

    @Test
    void unknownStatusAndNoRequestId() {
        assertThat(ProblemWriter.body(Rejection.of(599, "x", "y"), null)).containsEntry("title", "Error").doesNotContainKey("request_id");
    }

    @Test
    void doesNothingOnceCommitted() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.getOutputStream().write('x');
        response.flushBuffer();
        writer.write(response, Rejection.of(500, "internal", "boom"), "r");
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
