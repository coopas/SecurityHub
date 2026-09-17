package com.securityhub.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tagged so the suite can be filtered with -DexcludedGroups=integration on a machine without
 * a Docker daemon. The default `mvn test` run still executes it.
 */
@Tag("integration")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
@Import({DatabaseCleaner.class, TestDataFactory.class})
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected TestDataFactory fixtures;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    /**
     * Testcontainers is the default and what CI uses. An already-running PostgreSQL can be
     * substituted with -Dsecurityhub.test.jdbc-url=... for environments where the Docker
     * daemon is not reachable; the database must still be PostgreSQL 15, never H2.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        String externalUrl = System.getProperty("securityhub.test.jdbc-url");
        if (externalUrl != null && !externalUrl.trim().isEmpty()) {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username",
                    () -> System.getProperty("securityhub.test.jdbc-user", "securityhub"));
            registry.add("spring.datasource.password",
                    () -> System.getProperty("securityhub.test.jdbc-password", "securityhub"));
            return;
        }
        registry.add("spring.datasource.url", () -> PostgresContainer.getInstance().getJdbcUrl());
        registry.add("spring.datasource.username", () -> PostgresContainer.getInstance().getUsername());
        registry.add("spring.datasource.password", () -> PostgresContainer.getInstance().getPassword());
    }

    @BeforeEach
    void resetDatabase() {
        databaseCleaner.clean();
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Falha ao serializar payload de teste", ex);
        }
    }
}
