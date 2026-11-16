package dev.waypoint.gateway.tenant;

import dev.waypoint.gateway.path.PathPrefix;
import dev.waypoint.gateway.routing.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class TenantValidator {

    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    public static final int MAX_REQUESTS_PER_MINUTE = 100_000;

    private TenantValidator() {}

    public static Tenant validate(String id, TenantSpec spec) throws ValidationException {
        List<String> errors = new ArrayList<>();
        if (id == null || !ID.matcher(id).matches()) {
            errors.add("id must match " + ID.pattern());
        }
        if (spec == null) {
            throw new ValidationException(List.of("body is required"));
        }
        List<String> prefixes = spec.allowedPathPrefixes() == null ? List.of() : spec.allowedPathPrefixes();
        for (String prefix : prefixes) {
            if (!PathPrefix.isValid(prefix)) {
                errors.add("allowedPathPrefixes entry is not a canonical path: " + prefix);
            }
        }
        Integer rpm = spec.requestsPerMinute();
        if (rpm == null || rpm < 1 || rpm > MAX_REQUESTS_PER_MINUTE) {
            errors.add("requestsPerMinute must be between 1 and " + MAX_REQUESTS_PER_MINUTE);
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return new Tenant(id, prefixes, rpm,
                Boolean.TRUE.equals(spec.disabled()), Boolean.TRUE.equals(spec.auditRawPath()));
    }
}
