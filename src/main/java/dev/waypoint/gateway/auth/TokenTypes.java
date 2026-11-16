package dev.waypoint.gateway.auth;

import com.nimbusds.jose.JOSEObjectType;

public final class TokenTypes {

    public static final JOSEObjectType CUSTOMER = new JOSEObjectType("customer+jwt");
    public static final JOSEObjectType ADMIN = new JOSEObjectType("admin+jwt");
    public static final JOSEObjectType IDENTITY = new JOSEObjectType("identity+jwt");

    private TokenTypes() {}
}
