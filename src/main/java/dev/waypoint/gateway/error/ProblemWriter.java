package dev.waypoint.gateway.error;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** RFC 9457 {@code application/problem+json} with a machine-readable {@code code}; never a stack trace. */
public class ProblemWriter {

    public static final String MEDIA_TYPE = "application/problem+json";

    private final ObjectMapper mapper;

    public ProblemWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletResponse response, Rejection rejection, String requestId) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(rejection.status());
        rejection.headers().forEach(response::setHeader);
        response.setContentType(MEDIA_TYPE);
        response.getOutputStream().write(mapper.writeValueAsBytes(body(rejection, requestId)));
    }

    public static Map<String, Object> body(Rejection rejection, String requestId) {
        HttpStatus status = HttpStatus.resolve(rejection.status());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status != null ? status.getReasonPhrase() : "Error");
        body.put("status", rejection.status());
        body.put("detail", rejection.detail());
        body.put("code", rejection.code());
        if (requestId != null) {
            body.put("request_id", requestId);
        }
        return body;
    }
}
