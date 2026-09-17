package com.securityhub.security;

/** Builds a ready-to-use JwtService outside the Spring context, from the security package. */
public final class TestJwtServiceFactory {

    public static final String SECRET = "unit-test-secret-value-with-more-than-32-bytes-of-material";

    private TestJwtServiceFactory() {
    }

    public static JwtService create() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpirationMinutes(60);
        JwtService service = new JwtService(properties);
        service.init();
        return service;
    }
}
