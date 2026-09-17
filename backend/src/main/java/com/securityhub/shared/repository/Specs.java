package com.securityhub.shared.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filters are built as criteria predicates instead of JPQL with {@code :param is null}
 * guards. PostgreSQL cannot infer the type of a null bind parameter in that position and
 * answers "could not determine data type of parameter", so the JPQL form only works until
 * a filter is actually left blank. Omitting the predicate entirely also lets the planner
 * use the partial indexes.
 *
 * Every helper returns {@code null} for an absent value, which
 * {@link Specification#and(Specification)} ignores.
 */
public final class Specs {

    private Specs() {
    }

    /** For entities mapping the tenant as a {@code @ManyToOne company}. */
    public static <T> Specification<T> company(Long companyId) {
        return (root, query, cb) -> cb.equal(root.get("company").get("id"), companyId);
    }

    /** For entities holding the tenant as a plain {@code companyId} column. */
    public static <T> Specification<T> companyColumn(Long companyId) {
        return (root, query, cb) -> cb.equal(root.get("companyId"), companyId);
    }

    public static <T> Specification<T> eq(String attribute, Object value) {
        return value == null ? null : (root, query, cb) -> cb.equal(root.get(attribute), value);
    }

    public static <T> Specification<T> eqNested(String attribute, String nested, Object value) {
        return value == null ? null
                : (root, query, cb) -> cb.equal(root.get(attribute).get(nested), value);
    }

    public static <T> Specification<T> isNull(String attribute, Boolean expectNull) {
        if (expectNull == null) {
            return null;
        }
        return (root, query, cb) -> expectNull ? cb.isNull(root.get(attribute)) : cb.isNotNull(root.get(attribute));
    }

    public static <T> Specification<T> from(String attribute, Instant value) {
        return value == null ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get(attribute), value);
    }

    public static <T> Specification<T> to(String attribute, Instant value) {
        return value == null ? null
                : (root, query, cb) -> cb.lessThanOrEqualTo(root.get(attribute), value);
    }

    public static <T> Specification<T> before(String attribute, Instant value) {
        return value == null ? null
                : (root, query, cb) -> cb.lessThan(root.get(attribute), value);
    }

    /** Case-insensitive contains over any of the given attributes. */
    public static <T> Specification<T> containsAny(String term, String... attributes) {
        if (term == null || term.trim().isEmpty()) {
            return null;
        }
        String pattern = "%" + term.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> {
            List<Predicate> matches = new ArrayList<>();
            for (String attribute : attributes) {
                matches.add(cb.like(cb.lower(root.get(attribute)), pattern));
            }
            return cb.or(matches.toArray(new Predicate[0]));
        };
    }
}
