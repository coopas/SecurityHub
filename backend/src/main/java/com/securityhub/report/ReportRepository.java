package com.securityhub.report;

import com.securityhub.vulnerability.Vulnerability;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The two listings the executive report adds to the aggregations it reuses from
 * {@code DashboardService}. Everything else the report prints comes from the dashboard, so that
 * a figure on the PDF and the same figure on the screen can never disagree.
 *
 * <p>A repository of its own and not two more methods on {@code DashboardRepository}, whose
 * javadoc states it exposes the dashboard's queries and nothing else — the interface is the
 * boundary that keeps that true.
 *
 * <p>It extends the bare {@link Repository} marker for the same reason: read-only by
 * construction, with nothing inherited that could write, delete or load an entity graph.
 *
 * <p>The conventions of {@code DashboardRepository} apply here unchanged:
 * <ul>
 * <li>every query starts from {@code company_id}, which comes from the authenticated principal
 * and is never accepted from the request;</li>
 * <li>native queries return {@code List<Object[]>} and never an interface projection: a
 * projection binds by result-set alias and would silently yield nulls for the snake_case
 * aliases PostgreSQL reports here;</li>
 * <li>any numeric column is read through {@code ((Number) row[i])} rather than cast to its
 * apparent type, because with {@code nativeQuery = true} Hibernate 5 decides that type —
 * {@code cvss_score} arrives as {@link java.math.BigDecimal}, a {@code count(*)} as
 * {@link java.math.BigInteger}.</li>
 * </ul>
 *
 * <p>Both queries reach the project through {@code assets}: a vulnerability carries no
 * {@code project_id} on purpose, because an asset can be moved between projects and a copied id
 * would go stale. The join to {@code users} is a LEFT join — an unassigned finding is exactly
 * what an executive report must show, not hide.
 *
 * <p>The timestamps come back as text already rendered in UTC. The value the report prints is a
 * UTC day, and producing it in SQL keeps the session TimeZone — and the JVM default zone on the
 * way back — out of it, which is the same reasoning as {@code DashboardRepository.trend}.
 */
public interface ReportRepository extends Repository<Vulnerability, Long> {

    /**
     * Oldest first: on a critical-findings list the age of the oldest row is the point, so the
     * truncation at {@code :max} must drop the newest and never the oldest. The tie-break on the
     * id keeps the same ten rows in the same order between two generations of the report.
     */
    @Query(nativeQuery = true, value =
            "select v.title as title, p.name as project_name, a.name as asset_name, "
            + "u.name as assignee_name, "
            + "to_char(v.discovered_at at time zone 'UTC', 'YYYY-MM-DD') as discovered_day, "
            + "v.cvss_score as cvss_score, v.cve as cve "
            + "from vulnerabilities v "
            + "join assets a on a.id = v.asset_id "
            + "join projects p on p.id = a.project_id "
            + "left join users u on u.id = v.assigned_to "
            + "where v.company_id = :companyId "
            + "  and v.severity = 'CRITICAL' "
            + "  and v.status in ('OPEN','IN_PROGRESS') "
            + "order by v.discovered_at asc, v.id asc "
            + "limit :max")
    List<Object[]> criticalOpen(@Param("companyId") Long companyId, @Param("max") int max);

    /**
     * The predicate is the definition of §16, character for character the conjunction
     * {@code VulnerabilitySpecifications.overdue} builds for {@code GET /vulnerabilities} and
     * {@code DashboardRepository} counts for the card:
     * {@code due_date IS NOT NULL AND due_date < now AND status IN ('OPEN','IN_PROGRESS')}.
     * A report that disagreed with the screen it summarises would be the most visible possible
     * bug, and a third spelling of this rule is how that happens.
     *
     * <p>{@code now} is bound from Java, not {@code now()} in SQL, so the whole report describes
     * one instant and a test can pin it.
     */
    @Query(nativeQuery = true, value =
            "select v.title as title, v.severity as severity, "
            + "to_char(v.due_date at time zone 'UTC', 'YYYY-MM-DD') as due_day, "
            + "u.name as assignee_name, p.name as project_name "
            + "from vulnerabilities v "
            + "join assets a on a.id = v.asset_id "
            + "join projects p on p.id = a.project_id "
            + "left join users u on u.id = v.assigned_to "
            + "where v.company_id = :companyId "
            + "  and v.due_date is not null and v.due_date < :now "
            + "  and v.status in ('OPEN','IN_PROGRESS') "
            + "order by v.due_date asc, v.id asc "
            + "limit :max")
    List<Object[]> overdue(@Param("companyId") Long companyId, @Param("now") Instant now,
                           @Param("max") int max);
}
