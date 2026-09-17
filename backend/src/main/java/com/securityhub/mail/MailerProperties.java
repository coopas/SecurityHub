package com.securityhub.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Named MailerProperties and not MailProperties to avoid colliding with Spring Boot's own
 * {@code org.springframework.boot.autoconfigure.mail.MailProperties}, which binds
 * {@code spring.mail.*} and is already in the context.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "securityhub.mail")
public class MailerProperties {

    /** Sender of every transactional message. */
    private String from = "nao-responda@securityhub.local";

    /**
     * Base of the links that are sent: it points at the frontend, not at the API. Whoever
     * clicks needs a screen to type the new password into, not an endpoint.
     */
    private String appBaseUrl = "http://localhost:4200";
}
