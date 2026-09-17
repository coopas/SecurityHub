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
 * Uma linha por token emitido. O valor em claro nunca chega aqui: o que é persistido é o
 * SHA-256 hex dele (ADR 0006), e a linha existe para que revogar seja possível.
 *
 * Não há {@code company}: a tabela nunca é consultada por tenant e a empresa vem da linha de
 * users apontada por {@link #user}. Ver o comentário de V7.
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
     * Identificador da sessão: o login abre uma família e cada rotação insere o sucessor
     * dentro dela. É o que permite matar uma sessão inteira ao detectar reuso sem derrubar as
     * outras sessões da mesma pessoa.
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

    /** Instante da rotação. É a referência da janela de graça de 30 segundos. */
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
