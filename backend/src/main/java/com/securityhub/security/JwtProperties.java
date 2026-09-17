package com.securityhub.security;

import javax.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "securityhub.jwt")
public class JwtProperties {

    /** HMAC-SHA key material. Supplied by SECURITYHUB_JWT_SECRET; never hardcoded for prod. */
    private String secret;

    @Min(1)
    private long expirationMinutes = 60;

    @Min(1)
    private long refreshExpirationDays = 14;

    private String issuer = "securityhub";
}
