package com.securityhub.demo;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code securityhub.demo.*}. The prefix already existed in every profile but had no
 * consumer until {@link DemoDataSeeder}; this class is what finally reads it.
 *
 * <p>Picked up automatically by the {@code @ConfigurationPropertiesScan} on
 * {@code SecurityHubApplication}, so the bean exists in every profile. That is harmless:
 * the seeder itself is what carries {@code @Profile("demo")}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "securityhub.demo")
public class DemoProperties {

    /**
     * Second half of the gate. The {@code demo} profile keeps the seeder bean out of the
     * prod and test contexts entirely; this flag lets a hosted demo that must stay on the
     * {@code demo} profile stop re-seeding without changing its active profiles.
     */
    private boolean seedEnabled = false;

    /**
     * Shared password of every seeded account. Not a secret: see the comment in
     * {@code application-demo.yml}. Never logged.
     */
    private String password = "Demo@SecurityHub2026";
}
