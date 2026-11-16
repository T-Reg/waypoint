package dev.waypoint.gateway.admin;

import dev.waypoint.gateway.auth.AdminIdentity;
import dev.waypoint.gateway.store.PostgresConfigStore;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class AdminSupport {

    static final String SCOPE_ROUTES = "admin:routes";
    static final String SCOPE_TENANTS = "admin:tenants";
    static final String SCOPE_KEYS = "admin:keys";

    private static final Pattern ETAG = Pattern.compile("\"(\\d{1,18})\"");

    record Resource<T>(String id, long version, Instant updatedAt, String updatedBy, T spec) {}

    private AdminSupport() {}

    static void requireScope(AdminIdentity admin, String scope) {
        if (admin == null || !admin.scopes().contains(scope)) {
            throw new AdminException(403, "admin.scope_missing", "admin token lacks scope " + scope);
        }
    }

    /** Parses {@code If-Match: "<version>"}; absent means unconditional (create, or delete). */
    static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        Matcher matcher = ETAG.matcher(ifMatch.strip());
        if (!matcher.matches()) {
            throw new AdminException(400, "admin.invalid", "If-Match must be a strong ETag such as \"3\"");
        }
        return Long.parseLong(matcher.group(1));
    }

    static String etag(long version) {
        return "\"" + version + "\"";
    }

    static <T> ResponseEntity<Resource<T>> written(PostgresConfigStore.WriteResult result, Resource<T> body) {
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(etag(result.version())).body(body);
    }
}
