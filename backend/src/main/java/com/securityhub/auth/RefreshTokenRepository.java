package com.securityhub.auth;

import java.time.Instant;
import java.util.Optional;
import javax.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * {@code SELECT ... FOR UPDATE}. It is what makes the two-tabs case deterministic: without
     * the lock, two simultaneous requests with the same token would read the row while it is
     * still ACTIVE and each one would insert a successor, forking the family. With it, the
     * second transaction waits, re-reads the row already ROTATED and falls into the grace
     * window branch — which is a decision, not a race.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Kills the whole family, including the rows already ROTATED: in a theft, the earlier
     * rotations are exactly what the thief may be holding.
     *
     * The enums go in as a parameter and not as a literal in the JPQL. A literal
     * {@code 'REVOKED'} compiles, but it is compared as a String against a column mapped by
     * ordinal or by name depending on the entity, and a rename of the enum would go unnoticed
     * by the compiler.
     *
     * {@code updated_at} is assigned by hand because a bulk update does not go through the
     * AuditingEntityListener of BaseEntity, and the column is NOT NULL. The persistence context
     * is deliberately NOT cleared: the caller usually holds the entity it has just changed (the
     * deactivated user, the new password) and needs to map it to the response afterwards.
     */
    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.status = :revoked, t.revokedReason = :reason, "
            + "t.revokedAt = :now, t.updatedAt = :now "
            + "where t.familyId = :familyId and t.status <> :revoked")
    int revokeFamily(@Param("familyId") String familyId,
                     @Param("revoked") RefreshTokenStatus revoked,
                     @Param("reason") RevocationReason reason,
                     @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.status = :revoked, t.revokedReason = :reason, "
            + "t.revokedAt = :now, t.updatedAt = :now "
            + "where t.user.id = :userId and t.status <> :revoked")
    int revokeAllForUser(@Param("userId") Long userId,
                         @Param("revoked") RefreshTokenStatus revoked,
                         @Param("reason") RevocationReason reason,
                         @Param("now") Instant now);

    /**
     * Lazy collection: it runs on the login, where one extra row in the plan goes unnoticed,
     * instead of in a scheduler that would start up inside every integration test context
     * (ADR 0006).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.user.id = :userId and t.expiresAt < :cutoff")
    int purgeExpiredFor(@Param("userId") Long userId, @Param("cutoff") Instant cutoff);
}
