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
 * Issuing, rotation and revocation of the refresh tokens (ADR 0006).
 *
 * No {@code @PreAuthorize}: whoever presents a refresh token is not authenticated yet, and it is
 * precisely the row that is found — with the user it points at — that decides who the caller
 * is. The authorization here is possession of the secret.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    /**
     * Every refusal returns exactly this 401. Unknown, expired, revoked and reused are
     * indistinguishable from the outside: warning a thief that the theft was noticed only helps
     * the thief. The fact stays in the audit trail, which is where it belongs.
     */
    public static final String INVALID_SESSION = "Sessão inválida";

    /**
     * Two tabs, a network retry or a timeout make the same token arrive twice within seconds.
     * Without the window, reuse detection would log the legitimate user out on every benign
     * race. It does not help a thief in any relevant way: the stolen token would have to be
     * used within 30s of the victim's own rotation, and the family dies on the first reuse
     * outside that.
     */
    static final Duration REUSE_GRACE = Duration.ofSeconds(30);

    /** Slack before deleting an expired row, so that it can still serve an investigation. */
    static final Duration PURGE_GRACE = Duration.ofDays(7);

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final AuditService auditService;

    /** Opens a new session: new family, first row ACTIVE. Returns the plaintext value. */
    @Transactional
    public String issue(User user) {
        return issueInFamily(user, UUID.randomUUID().toString(), Instant.now());
    }

    /**
     * Runs the decision table of ADR 0006 in the order in which it is written.
     *
     * {@code noRollbackFor} is not a detail: in the reuse and deactivated-user branches the
     * refusal comes with effects that have to survive it — the revocation of the family and
     * the audit row. Under the default rollback rule, throwing the exception would undo
     * exactly the defence the branch has just set up, and the thief could try again.
     * A {@code REQUIRES_NEW} would solve the same problem by deadlocking: the inner transaction
     * would wait for the row lock this one is holding.
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
            // No audit entry: a client in a retry loop with an already revoked token would
            // flood the trail with rows that add no new fact at all.
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
            // Deactivating already revokes everything; getting here means the users row was
            // changed from the outside. The session dies with it, instead of surviving until
            // its natural expiry.
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
     * Ends only the family that was presented, and returns silence for an unknown token:
     * {@code POST /auth/logout} answers 204 in every case so as not to become an oracle for
     * the existence of a session.
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

    /** Used by the role change, by the deactivation and by the password reset. */
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
        // A null usedAt on a ROTATED row would only happen through a direct write to the
        // database; treating it as outside the window is the safe side.
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
     * The result carries the id of the user, and not the entity: the response is assembled
     * outside this transaction (see {@code AuthService.refresh}) and a lazy proxy loaded here
     * would already be detached there.
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
