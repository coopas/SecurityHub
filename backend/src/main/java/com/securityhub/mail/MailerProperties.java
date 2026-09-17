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

    /** Remetente de todas as mensagens transacionais. */
    private String from = "nao-responda@securityhub.local";

    /**
     * Base dos links enviados: aponta para o frontend, não para a API. Quem clica precisa de
     * uma tela onde digitar a senha nova, não de um endpoint.
     */
    private String appBaseUrl = "http://localhost:4200";
}
