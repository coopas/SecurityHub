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
 * Convite pendente para entrar em uma empresa.
 *
 * A linha de {@code users} só nasce no aceite. É o motivo de existir esta tabela em vez de um
 * usuário inativo esperando: um convite de ADMIN pendente não é um administrador, e contá-lo
 * como tal deixaria a empresa sem nenhum administrador de verdade quando o último existente se
 * desativasse confiando na regra do "último administrador ativo".
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
     * Aceitar e carimbar o instante andam juntos porque V7 exige a equivalência entre os dois;
     * separá-los em dois setters deixaria a violação da CHECK possível de escrever.
     */
    public void accept(Instant when) {
        this.status = InvitationStatus.ACCEPTED;
        this.acceptedAt = when;
    }

    public boolean isPending() {
        return status == InvitationStatus.PENDING;
    }
}
