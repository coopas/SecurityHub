package com.securityhub.dashboard;

import com.securityhub.vulnerability.Vulnerability;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately extends the bare {@link Repository} marker instead of {@code JpaRepository}:
 * the dashboard only ever reads aggregates, so the interface exposes the five queries below
 * and nothing that could write, delete or load an entity graph by accident.
 *
 * Every query starts from {@code company_id}. The parameter comes from the authenticated
 * principal (docs/permissions.md) and is never accepted from the request.
 *
 * Two conventions worth keeping in mind when touching this file:
 * <ul>
 * <li>the native queries return {@code List<Object[]>} and not an interface projection,
 * because a projection binds by result-set alias and would silently yield nulls for
 * the snake_case aliases PostgreSQL reports here;</li>
 * <li>with {@code nativeQuery = true} Hibernate 5 hands {@code count(*)} back as
 * {@link java.math.BigInteger}, so every column must be read through
 * {@code ((Number) row[i]).longValue} rather than cast to {@code Long}.</li>
 * </ul>
 */
public interface DashboardRepository extends Repository<Vulnerability, Long> {

    /**
     * One row with every scalar of the summary card set. A single pass over the tenant's
     * rows with {@code FILTER} is cheaper than five round trips, and it also guarantees the
     * five numbers describe the same snapshot.
     *
     * {@code overdue} is the definition of §16, character for character the same conjunction
     * {@code VulnerabilitySpecifications.overdue} builds for {@code GET /vulnerabilities}:
     * {@code due_date IS NOT NULL AND due_date < now AND status IN ('OPEN','IN_PROGRESS')}.
     * A card that disagreed with its own drill-down would be the most visible possible bug.
     * {@code now} is bound from Java instead of calling SQL {@code now} so the value is
     * deterministic and a test can pin it.
     *
     * The counts are {@code count(*)} and not {@code count(id)} on purpose: without the id
     * column the whole aggregate is served by an index-only scan (see V6).
     */
    @Query(nativeQuery = true, value =
            "select count(*) as total_count, "
            + "count(*) filter (where v.status in ('OPEN','IN_PROGRESS')) as open_count, "
            + "count(*) filter (where v.status in ('OPEN','IN_PROGRESS') "
            + "                 and v.severity = 'CRITICAL') as critical_open_count, "
            + "count(*) filter (where v.due_date is not null and v.due_date < :now "
            + "                 and v.status in ('OPEN','IN_PROGRESS')) as overdue_count, "
            + "count(*) filter (where v.status = 'RESOLVED') as resolved_count "
            + "from vulnerabilities v "
            + "where v.company_id = :companyId")
    List<Object[]> summaryCounts(@Param("companyId") Long companyId, @Param("now") Instant now);

    /**
     * Per-project breakdown, truncated. Vulnerabilities carry no {@code project_id} — an
     * asset may be moved between projects, so a copy would go stale (§16) — hence the two
     * joins up to {@code projects}.
     *
     * The join is inner, which is exactly the wanted behaviour: a project with no finding
     * has nothing to show on a vulnerability dashboard and would only push a real project
     * out of the top ten. The tie-breaker on the name keeps the list stable between reloads.
     */
    @Query(nativeQuery = true, value =
            "select p.id as project_id, p.name as project_name, "
            + "count(*) as total_count, "
            + "count(*) filter (where v.status in ('OPEN','IN_PROGRESS')) as open_count, "
            + "count(*) filter (where v.due_date is not null and v.due_date < :now "
            + "                 and v.status in ('OPEN','IN_PROGRESS')) as overdue_count "
            + "from vulnerabilities v "
            + "join assets a on a.id = v.asset_id "
            + "join projects p on p.id = a.project_id "
            + "where v.company_id = :companyId "
            + "group by p.id, p.name "
            + "order by count(*) desc, p.name asc "
            + "limit :maxProjects")
    List<Object[]> topProjects(@Param("companyId") Long companyId, @Param("now") Instant now,
                               @Param("maxProjects") int maxProjects);

    /**
     * JPQL and not native: the enum comes back as a typed {@link com.securityhub.vulnerability.Severity}
     * and the count as a {@link Long}, so neither needs the BigInteger dance above. Absent
     * severities are zero-filled in Java, which is also what fixes the chart legend order.
     */
    @Query("select v.severity, count(*) from Vulnerability v "
            + "where v.company.id = :companyId group by v.severity")
    List<Object[]> countBySeverity(@Param("companyId") Long companyId);

    /** Twin of {@link #countBySeverity}; two near-identical queries beat one generic helper. */
    @Query("select v.status, count(*) from Vulnerability v "
            + "where v.company.id = :companyId group by v.status")
    List<Object[]> countByStatus(@Param("companyId") Long companyId);

    /**
     * Daily time series with two independent series, opened and resolved.
     *
     * Three things here are load-bearing:
     * <ol>
     * <li>each series is its own pre-aggregated subquery. Joining {@code vulnerabilities}
     * twice directly would multiply rows — a day with 3 opened and 2 resolved would
     * report 6 for both;</li>
     * <li>the bucket is {@code cast(ts at time zone 'UTC' as date)}. Casting the
     * {@code timestamptz} straight to {@code date} would use the session TimeZone, so
     * the same row would land on different days depending on who connected. The
     * {@code cast(... as ...)} spelling is not cosmetic: PostgreSQL's {@code ::}
     * operator collides with the named-parameter syntax of the query parser, which
     * leaves a stray colon behind and fails with "syntax error at or near :";</li>
     * <li>the day is returned as {@code to_char(...)} text. Reading a {@code java.sql.Date}
     * converts through the JVM default zone and can hand back the previous day.</li>
     * </ol>
     *
     * {@code generate_series} fills the empty days in SQL, so the response always has exactly
     * one point per day of the window. The instant bounds are bound as {@code Instant} so the
     * range predicate stays sargable against the index on each timestamp column.
     */
    @Query(nativeQuery = true, value =
            "select to_char(d.day, 'YYYY-MM-DD') as bucket, "
            + "coalesce(o.total, 0) as opened_count, "
            + "coalesce(r.total, 0) as resolved_count "
            + "from generate_series(cast(:fromDay as date), cast(:toDay as date), interval '1 day') as d(day) "
            + "left join ("
            + "  select cast(v.discovered_at at time zone 'UTC' as date) as day, count(*) as total "
            + "  from vulnerabilities v "
            + "  where v.company_id = :companyId "
            + "    and v.discovered_at >= :fromInstant and v.discovered_at < :toInstant "
            + "  group by 1"
            + ") o on o.day = cast(d.day as date) "
            + "left join ("
            + "  select cast(v.resolved_at at time zone 'UTC' as date) as day, count(*) as total "
            + "  from vulnerabilities v "
            + "  where v.company_id = :companyId "
            + "    and v.resolved_at >= :fromInstant and v.resolved_at < :toInstant "
            + "  group by 1"
            + ") r on r.day = cast(d.day as date) "
            + "order by d.day")
    List<Object[]> trend(@Param("companyId") Long companyId,
                         @Param("fromDay") String fromDay,
                         @Param("toDay") String toDay,
                         @Param("fromInstant") Instant fromInstant,
                         @Param("toInstant") Instant toInstant);
}
