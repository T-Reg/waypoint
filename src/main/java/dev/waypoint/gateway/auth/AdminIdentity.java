package dev.waypoint.gateway.auth;

import java.util.Set;

public record AdminIdentity(String subject, Set<String> scopes) {

    public AdminIdentity {
        scopes = Set.copyOf(scopes);
    }
}
