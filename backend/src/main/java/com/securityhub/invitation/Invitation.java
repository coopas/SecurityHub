package com.securityhub.invitation;

import com.securityhub.company.Company;
import com.securityhub.shared.model.BaseEntity;
import com.securityhub.user.Role;
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
 * Pending invitation to join a company.
 *
 * The {@code users} row is only born on acceptance. That is the reason this table exists
 * instead of an inactive user waiting around: a pending ADMIN invitation is not an
 * administrator, and counting it as one would leave the company with no real administrator at
 * all the moment the last existing one deactivated itself trusting the "last active
 * administrator" rule.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "invitations")
public class Invitation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 180)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvitationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by", nullable = false)
    private User invitedBy;

    public Invitation(Company company, String name, String email, Role role, String tokenHash,
                      Instant expiresAt, User invitedBy) {
        this.company = company;
        this.name = name;
        this.email = User.normalizeEmail(email);
        this.role = role;
        this.tokenHash = tokenHash;
        this.status = InvitationStatus.PENDING;
        this.expiresAt = expiresAt;
        this.invitedBy = invitedBy;
    }

    public void setEmail(String email) {
        this.email = User.normalizeEmail(email);
    }

    /**
     * Accepting and stamping the instant go together because V7 demands the equivalence between
     * the two; splitting them into two setters would leave the CHECK violation writable.
     */
    public void accept(Instant when) {
        this.status = InvitationStatus.ACCEPTED;
        this.acceptedAt = when;
    }

    public boolean isPending() {
        return status == InvitationStatus.PENDING;
    }
}
