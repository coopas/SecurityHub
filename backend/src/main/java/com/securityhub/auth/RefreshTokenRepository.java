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
     * {@code SELECT ... FOR UPDATE}. É o que torna determinístico o caso das duas abas: sem o
     * lock, duas requisições simultâneas com o mesmo token leriam a linha ainda ACTIVE e
     * cada uma inseriria um sucessor, bifurcando a família. Com ele, a segunda transação
     * espera, relê a linha já ROTATED e cai no ramo da janela de graça — que é uma decisão,
     * não uma corrida.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Mata a família inteira, inclusive as linhas já ROTATED: em um roubo, as rotações
     * anteriores são exatamente o que o ladrão pode ter em mãos.
     *
     * Os enums entram como parâmetro e não como literal na JPQL. Um literal {@code 'REVOKED'}
     * compila, mas é comparado como String contra uma coluna mapeada por ordinal ou por nome
     * conforme a entidade, e um rename do enum passaria despercebido pelo compilador.
     *
     * {@code updated_at} é atribuído à mão porque uma atualização em massa não passa pelo
     * AuditingEntityListener do BaseEntity, e a coluna é NOT NULL. O contexto de persistência
     * deliberadamente NÃO é limpo: quem chama costuma ter na mão a entidade que acabou de
     * alterar (o usuário desativado, a senha nova) e precisa mapeá-la para a resposta depois.
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
     * Coleta preguiçosa: roda no login, onde uma linha a mais no plano não é percebida, em vez
     * de em um agendador que subiria dentro de todo contexto de teste de integração (ADR 0006).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.user.id = :userId and t.expiresAt < :cutoff")
    int purgeExpiredFor(@Param("userId") Long userId, @Param("cutoff") Instant cutoff);
}
