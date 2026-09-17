package com.securityhub.auth;

import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.security.JwtService;
import com.securityhub.shared.error.UnauthorizedException;
import com.securityhub.shared.token.SecretTokens;
import com.securityhub.user.User;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emissão, rotação e revogação dos refresh tokens (ADR 0006).
 *
 * Sem {@code @PreAuthorize}: quem apresenta um refresh token ainda não está autenticado, e é
 * justamente a linha encontrada — com o usuário que ela aponta — que decide quem é o chamador.
 * A autorização aqui é a posse do segredo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    /**
     * Toda recusa devolve exatamente este 401. Desconhecido, expirado, revogado e reusado são
     * indistinguíveis de fora: avisar um ladrão de que o roubo foi percebido só ajuda o ladrão.
     * O fato fica na auditoria, que é onde ele pertence.
     */
    public static final String INVALID_SESSION = "Sessão inválida";

    /**
     * Duas abas, um retry de rede ou um timeout fazem o mesmo token chegar duas vezes em
     * segundos. Sem a janela, a detecção de reuso deslogaria o usuário legítimo em toda
     * corrida benigna. Ela não ajuda um ladrão de forma relevante: o token roubado teria que
     * ser usado dentro de 30s da rotação da própria vítima, e a família morre no primeiro
     * reuso fora disso.
     */
    static final Duration REUSE_GRACE = Duration.ofSeconds(30);

    /** Folga antes de apagar uma linha vencida, para que ela ainda sirva a uma investigação. */
    static final Duration PURGE_GRACE = Duration.ofDays(7);

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final AuditService auditService;

    /** Abre uma sessão nova: família nova, primeira linha ACTIVE. Devolve o valor em claro. */
    @Transactional
    public String issue(User user) {
        return issueInFamily(user, UUID.randomUUID().toString(), Instant.now());
    }

    /**
     * Executa a tabela de decisão do ADR 0006 na ordem em que ela está escrita.
     *
     * {@code noRollbackFor} não é um detalhe: nos ramos de reuso e de usuário desativado a
     * recusa vem acompanhada de efeitos que precisam sobreviver a ela — a revogação da família
     * e a linha de auditoria. Com a regra padrão de rollback, lançar a exceção desfaria
     * exatamente a defesa que o ramo acabou de armar, e o ladrão poderia tentar de novo.
     * Um {@code REQUIRES_NEW} resolveria o mesmo problema travando: a transação interna
     * esperaria pelo lock de linha que esta aqui segura.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Rotation rotate(String presentedToken) {
        Instant now = Instant.now();
        Optional<RefreshToken> found =
                refreshTokenRepository.findByTokenHashForUpdate(SecretTokens.hash(presentedToken));
        if (!found.isPresent()) {
            throw invalidSession();
        }

        RefreshToken token = found.get();
        if (token.getStatus() == RefreshTokenStatus.REVOKED) {
            // Nada de auditoria: um cliente em laço de retry com um token já revogado
            // inundaria a trilha com linhas que não acrescentam nenhum fato novo.
            throw invalidSession();
        }
        if (token.getExpiresAt().isBefore(now)) {
            throw invalidSession();
        }

        if (token.getStatus() == RefreshTokenStatus.ROTATED) {
            if (withinGraceWindow(token, now)) {
                return new Rotation(token.getUser().getId(),
                        issueInFamily(token.getUser(), token.getFamilyId(), now));
            }
            detectReuse(token, now);
            throw invalidSession();
        }

        User user = token.getUser();
        if (!user.isActive()) {
            // Desativar já revoga tudo; chegar aqui significa que a linha de users mudou por
            // fora. A sessão morre junto, em vez de sobreviver até o vencimento natural.
            refreshTokenRepository.revokeAllForUser(user.getId(), RefreshTokenStatus.REVOKED,
                    RevocationReason.USER_DEACTIVATED, now);
            throw invalidSession();
        }

        token.setStatus(RefreshTokenStatus.ROTATED);
        token.setUsedAt(now);
        refreshTokenRepository.save(token);
        return new Rotation(user.getId(), issueInFamily(user, token.getFamilyId(), now));
    }

    /**
     * Encerra apenas a família apresentada, e devolve silêncio para um token desconhecido:
     * {@code POST /auth/logout} responde 204 em qualquer caso para não virar um oráculo de
     * existência de sessão.
     */
    @Transactional
    public void logout(String presentedToken) {
        Optional<RefreshToken> found =
                refreshTokenRepository.findByTokenHash(SecretTokens.hash(presentedToken));
        if (!found.isPresent()) {
            return;
        }
        RefreshToken token = found.get();
        Long userId = token.getUser().getId();
        Long companyId = token.getUser().getCompany().getId();
        String email = token.getUser().getEmail();
        int revoked = refreshTokenRepository.revokeFamily(token.getFamilyId(),
                RefreshTokenStatus.REVOKED, RevocationReason.LOGOUT, Instant.now());

        auditService.record(AuditEntry.ofActor(companyId, userId, email, AuditAction.LOGOUT,
                "User", userId));
        log.info("Logout encerrou {} token(s) da sessão do usuário {}", revoked, userId);
    }

    /** Usada pela troca de papel, pela desativação e pela redefinição de senha. */
    @Transactional
    public int revokeAllForUser(Long userId, RevocationReason reason) {
        return refreshTokenRepository.revokeAllForUser(userId, RefreshTokenStatus.REVOKED, reason,
                Instant.now());
    }

    @Transactional
    public int purgeExpiredFor(Long userId) {
        return refreshTokenRepository.purgeExpiredFor(userId, Instant.now().minus(PURGE_GRACE));
    }

    private String issueInFamily(User user, String familyId, Instant now) {
        String plaintext = SecretTokens.random();
        refreshTokenRepository.save(new RefreshToken(user, familyId, SecretTokens.hash(plaintext),
                now.plus(jwtService.refreshTokenTtl())));
        return plaintext;
    }

    private boolean withinGraceWindow(RefreshToken token, Instant now) {
        // usedAt nulo em uma linha ROTATED só aconteceria por escrita direta no banco; tratar
        // como fora da janela é o lado seguro.
        return token.getUsedAt() != null && !now.isAfter(token.getUsedAt().plus(REUSE_GRACE));
    }

    private void detectReuse(RefreshToken token, Instant now) {
        Long userId = token.getUser().getId();
        Long companyId = token.getUser().getCompany().getId();
        String email = token.getUser().getEmail();
        String familyId = token.getFamilyId();

        int revoked = refreshTokenRepository.revokeFamily(familyId, RefreshTokenStatus.REVOKED,
                RevocationReason.REUSE_DETECTED, now);
        auditService.record(AuditEntry.ofActor(companyId, userId, email,
                AuditAction.TOKEN_REUSE_DETECTED, "User", userId));
        log.warn("Reuso de refresh token detectado: {} linha(s) da família do usuário {} revogadas",
                revoked, userId);
    }

    private UnauthorizedException invalidSession() {
        return new UnauthorizedException(INVALID_SESSION);
    }

    /**
     * O resultado carrega o id do usuário, e não a entidade: a resposta é montada fora desta
     * transação (ver {@code AuthService.refresh}) e um proxy lazy carregado aqui já estaria
     * desanexado lá.
     */
    @Getter
    public static final class Rotation {

        private final Long userId;
        private final String refreshToken;

        Rotation(Long userId, String refreshToken) {
            this.userId = userId;
            this.refreshToken = refreshToken;
        }
    }
}
