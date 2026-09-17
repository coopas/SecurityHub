package com.securityhub.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One container for the whole suite. Starting a PostgreSQL per test class costs several
 * seconds each and the schema is recreated by Flyway anyway, so the instance is shared and
 * torn down by the Ryuk reaper when the JVM exits.
 */
public final class PostgresContainer {

    private static final PostgreSQLContainer<?> INSTANCE =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("securityhub_test")
                    .withUsername("securityhub")
                    .withPassword("securityhub")
                    .withReuse(false);

    static {
        INSTANCE.start();
    }

    private PostgresContainer() {
    }

    public static PostgreSQLContainer<?> getInstance() {
        return INSTANCE;
    }
}
