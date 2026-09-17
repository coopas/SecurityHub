package com.securityhub.auth;

import com.securityhub.shared.model.BaseEntity;
import com.securityhub.user.User;
import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per issued token. The plaintext value never reaches here: what is persisted is its
 * hex SHA-256 (ADR 0006), and the row exists so that revoking is possible.
 *
 * There is no {@code company}: the table is never queried by tenant and the company comes from
 * the users row pointed at by {@link #user}. See the comment of V7.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Identifier of the session: the login opens a family and every rotation inserts the
     * successor inside it. It is what makes it possible to kill an entire session on reuse
     * detection without bringing down the other sessions of the same person.
     */
    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefreshTokenStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 20)
    private RevocationReason revokedReason;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Instant of the rotation. It is the reference for the 30-second grace window. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public RefreshToken(User user, String familyId, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.status = RefreshTokenStatus.ACTIVE;
        this.expiresAt = expiresAt;
    }
}
