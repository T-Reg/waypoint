package dev.waypoint.gateway.admin;

import dev.waypoint.gateway.error.ProblemWriter;
import dev.waypoint.gateway.error.Rejection;
import dev.waypoint.gateway.routing.ValidationException;
import dev.waypoint.gateway.store.ConfigConflictException;
import dev.waypoint.gateway.store.ConfigNotFoundException;
import dev.waypoint.gateway.store.PreconditionRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice(basePackageClasses = AdminExceptionHandler.class)
public class AdminExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminExceptionHandler.class);

    @ExceptionHandler(AdminException.class)
    ResponseEntity<Map<String, Object>> admin(AdminException e, HttpServletRequest request) {
        return problem(request, Rejection.of(e.status(), e.code(), e.getMessage()));
    }

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<Map<String, Object>> invalid(ValidationException e, HttpServletRequest request) {
        return problem(request, Rejection.of(400, "admin.invalid", e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<Map<String, Object>> unreadable(Exception e, HttpServletRequest request) {
        return problem(request, Rejection.of(400, "admin.invalid", "request body must be valid JSON with known fields only"));
    }

    @ExceptionHandler(ConfigConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(ConfigConflictException e, HttpServletRequest request) {
        return problem(request, Rejection.of(409, "admin.conflict", e.getMessage()));
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    ResponseEntity<Map<String, Object>> preconditionRequired(PreconditionRequiredException e, HttpServletRequest request) {
        return problem(request, Rejection.of(428, "admin.precondition_required", e.getMessage()));
    }

    @ExceptionHandler(ConfigNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(ConfigNotFoundException e, HttpServletRequest request) {
        return problem(request, Rejection.of(404, "admin.not_found", e.getMessage()));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<Map<String, Object>> noHandler(Exception e, HttpServletRequest request) {
        return problem(request, Rejection.of(404, "admin.not_found", "not found"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return problem(request, Rejection.of(405, "admin.method_not_allowed", "method not allowed"));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Map<String, Object>> storeDown(RuntimeException e, HttpServletRequest request) {
        log.warn("Admin request failed: config store unavailable: {}", e.getMessage());
        return problem(request, Rejection.of(503, "admin.store_unavailable", "config store is unavailable; nothing was changed"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected admin error", e);
        return problem(request, Rejection.of(500, "internal", "internal error"));
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpServletRequest request, Rejection rejection) {
        request.setAttribute(AdminGate.DENY_CODE_ATTRIBUTE, rejection.code());
        return ResponseEntity.status(rejection.status())
                .contentType(MediaType.parseMediaType(ProblemWriter.MEDIA_TYPE))
                .body(ProblemWriter.body(rejection, (String) request.getAttribute(AdminGate.REQUEST_ID_ATTRIBUTE)));
    }
}
