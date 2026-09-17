package com.securityhub.support;

import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Truncates instead of rolling back a transaction so that each test exercises the real
 * commit path, including deferred constraints and database-level checks.
 */
@RequiredArgsConstructor
public class DatabaseCleaner {

    private final JdbcTemplate jdbcTemplate;
    private String truncateStatement;

    @PostConstruct
    void resolveTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                        + "AND tablename <> 'flyway_schema_history'",
                String.class);
        this.truncateStatement = tables.isEmpty() ? null
                : "TRUNCATE TABLE " + tables.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", "))
                        + " RESTART IDENTITY CASCADE";
    }

    public void clean() {
        if (truncateStatement != null) {
            jdbcTemplate.execute(truncateStatement);
        }
    }
}
