package com.securityhub.auth;

import com.securityhub.shared.model.BaseEntity;
import com.securityhub.user.User;
import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Um link de redefinição vivo. Sem status: usar o token apaga a linha, e pedir outro apaga o
 * anterior, então "existe" já quer dizer "vale". A unicidade de {@code user_id} em V7 é o que
 * garante que só exista um por vez.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "password_reset_tokens")
public class PasswordResetToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public PasswordResetToken(User user, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }
}
