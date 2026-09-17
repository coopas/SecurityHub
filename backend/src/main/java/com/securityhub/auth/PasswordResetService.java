package com.securityhub.auth;

import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.auth.dto.PasswordResetConfirmRequest;
import com.securityhub.auth.dto.PasswordResetRequest;
import com.securityhub.mail.Mailer;
import com.securityhub.mail.MailerProperties;
import com.securityhub.mail.MailTemplates;
import com.securityhub.shared.error.BadRequestException;
import com.securityhub.shared.token.SecretTokens;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password reset through a single-use link.
 *
 * Both ends are deliberately blind: the request answers 202 for any address and the confirmation
 * answers the same 400 for a token that is unknown, expired or belongs to a deactivated account.
 * An anonymous visitor on a public page cannot find out who has an account here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    /**
     * 400 and not 401. The 401 is the signal the frontend interceptor translates as "sua sessão
     * expirou": it would clear local storage and redirect to the login. Whoever is on this screen
     * is anonymous on a public page, has no session at all to expire, and would get a redirect
     * in place of the message that explains what happened.
     */
    static final String INVALID_LINK = "Link de redefinição inválido ou expirado";

    private static final String ENTITY_TYPE = "User";

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final Mailer mailer;
    private final MailerProperties mailerProperties;

    /**
     * Always 202, byte for byte the same, known address or not.
     *
     * There is no dummy BCrypt here, unlike {@code AuthService.login}: on that path the password
     * verification is the expensive work that has to happen in the unknown e-mail case as well,
     * or else the response time gives the difference away. Here no password is verified at all
     * — the expensive work would be the trip to SMTP, and it leaves the request thread
     * (ADR 0007), so that both paths spend one indexed query and nothing more.
     */
    @Transactional
    public void request(PasswordResetRequest request) {
        String email = User.normalizeEmail(request.getEmail());
        Optional<User> found = userRepository.findByEmailAndActiveTrue(email);
        if (!found.isPresent()) {
            // Without the address in the log: the trail of a multi-tenant system is no place
            // to record who tried to exist.
            log.info("Pedido de redefinição para endereço desconhecido ou inativo, ignorado");
            return;
        }

        User user = found.get();
        passwordResetTokenRepository.deleteByUserId(user.getId());

        String plaintext = SecretTokens.random();
        Instant now = Instant.now();
        passwordResetTokenRepository.save(new PasswordResetToken(user, SecretTokens.hash(plaintext),
                now.plus(TOKEN_TTL)));

        // Independent because the users row it references is already committed and the fact
        // "somebody asked for a reset" has value even if something further ahead undoes the
        // transaction.
        auditService.recordIndependently(AuditEntry.ofActor(user.getCompany().getId(), user.getId(),
                user.getEmail(), AuditAction.PASSWORD_RESET, ENTITY_TYPE, user.getId()));

        mailer.sendAfterCommit(user.getEmail(), MailTemplates.PASSWORD_RESET_SUBJECT,
                MailTemplates.passwordReset(user.getName(), resetLink(plaintext),
                        TOKEN_TTL.toMinutes()));
        log.info("Link de redefinição emitido para o usuário {}", user.getId());
    }

    /**
     * Returns 204 and not a session, unlike invitation acceptance. The asymmetry is deliberate:
     * whoever accepts an invitation has proved possession of the e-mail in order to create the
     * account and has no other credential to use, while whoever resets the password has just
     * chosen one and the honest path is to go to the login with it. Issuing a session here would
     * turn an intercepted e-mail link into a ready-made session, without a single extra step.
     */
    @Transactional
    public void confirm(PasswordResetConfirmRequest request) {
        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenHash(SecretTokens.hash(request.getToken()))
                .orElseThrow(() -> new BadRequestException(INVALID_LINK));
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException(INVALID_LINK);
        }

        User user = token.getUser();
        if (!user.isActive()) {
            throw new BadRequestException(INVALID_LINK);
        }

        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);
        // Single use: the row disappears instead of gaining a state. lastLoginAt is left
        // untouched — resetting a password is not signing in.
        passwordResetTokenRepository.delete(token);
        refreshTokenService.revokeAllForUser(user.getId(), RevocationReason.PASSWORD_RESET);

        auditService.record(AuditEntry.ofActor(user.getCompany().getId(), user.getId(),
                user.getEmail(), AuditAction.PASSWORD_RESET, ENTITY_TYPE, user.getId()));
        log.info("Senha redefinida para o usuário {}", user.getId());
    }

    /**
     * The link points at the frontend, not at the API: the recipient needs a screen on which to
     * type the new password.
     */
    private String resetLink(String plaintext) {
        return mailerProperties.getAppBaseUrl() + "/reset-password?token=" + encode(plaintext);
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("UTF-8 indisponível nesta JVM", ex);
        }
    }
}
