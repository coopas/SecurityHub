package com.securityhub.invitation;

import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.auth.AuthService;
import com.securityhub.auth.dto.AuthResponse;
import com.securityhub.company.Company;
import com.securityhub.invitation.dto.InvitationAcceptRequest;
import com.securityhub.invitation.dto.InvitationPreviewResponse;
import com.securityhub.invitation.dto.InvitationRequest;
import com.securityhub.invitation.dto.InvitationResponse;
import com.securityhub.mail.Mailer;
import com.securityhub.mail.MailerProperties;
import com.securityhub.mail.MailTemplates;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.error.BadRequestException;
import com.securityhub.shared.error.ConflictException;
import com.securityhub.shared.error.NotFoundException;
import com.securityhub.shared.token.SecretTokens;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations to join a company. Both ends of the acceptance are public: whoever clicks the
 * link has no account yet, so possession of the token is the only credential possible.
 *
 * The authorization of the administrative operations lives here and not in the controller, as
 * in the rest of the project (docs/permissions.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvitationService {

    static final Duration TOKEN_TTL = Duration.ofDays(7);
    static final String ENTITY_TYPE = "Invitation";

    /**
     * The same message as {@code AuthService.register}. It covers three cases the inviter must
     * not be able to tell apart: the address already has an account in this company, has an
     * account in another one, or has a live invitation in another one. A specific message for
     * each would tell a curious administrator in which other company of the product that
     * e-mail shows up.
     */
    static final String EMAIL_TAKEN = "E-mail já cadastrado";

    /** Unknown, expired, revoked and already accepted are the same 400 to whoever presents it. */
    static final String INVALID_INVITATION = "Convite inválido ou expirado";

    private final InvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final Mailer mailer;
    private final MailerProperties mailerProperties;

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public InvitationResponse create(AuthenticatedUser current, InvitationRequest request) {
        String email = User.normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(EMAIL_TAKEN);
        }
        replacePendingInvitation(current, email);

        User inviter = userRepository.findByIdAndCompanyId(current.getId(), current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Usuário", current.getId()));
        String plaintext = SecretTokens.random();
        Invitation invitation = new Invitation(inviter.getCompany(), request.getName().trim(), email,
                request.getRole(), SecretTokens.hash(plaintext), Instant.now().plus(TOKEN_TTL), inviter);
        invitationRepository.save(invitation);

        auditService.record(AuditEntry.created(current, ENTITY_TYPE, invitation.getId(),
                snapshot(invitation)));
        mailer.sendAfterCommit(email, MailTemplates.INVITATION_SUBJECT,
                MailTemplates.invitation(invitation.getName(), inviter.getCompany().getName(),
                        inviter.getName(), acceptLink(plaintext), TOKEN_TTL.toDays()));
        log.info("Convite {} emitido para o papel {} na empresa {}", invitation.getId(),
                invitation.getRole(), current.getCompanyId());
        // Mapped in here: open-in-view is off and company/invitedBy are lazy.
        return InvitationMapper.toResponse(invitation);
    }

    /**
     * A plain array and no pagination, like {@code GET /users}: there are few rows per company
     * and the administration screen shows them all at once.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<InvitationResponse> list(AuthenticatedUser current) {
        return invitationRepository.findByCompanyIdOrderByCreatedAtDesc(current.getCompanyId())
                .stream()
                .map(InvitationMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void revoke(AuthenticatedUser current, Long id) {
        Invitation invitation = invitationRepository.findByIdAndCompanyId(id, current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Convite", id));
        if (!invitation.isPending()) {
            // Besides making no sense, revoking an accepted invitation would violate the
            // equivalence between the ACCEPTED status and accepted_at that V7 checks.
            throw new ConflictException("Convite já foi aceito ou revogado");
        }

        Map<String, Object> before = snapshot(invitation);
        invitation.setStatus(InvitationStatus.REVOKED);
        invitationRepository.save(invitation);

        auditService.record(AuditEntry.updated(current, ENTITY_TYPE, id, before, snapshot(invitation)));
        log.info("Convite {} revogado na empresa {}", id, current.getCompanyId());
    }

    /** Public preview: whoever arrives by the link needs to know where they are being invited. */
    @Transactional(readOnly = true)
    public InvitationPreviewResponse preview(String token) {
        return InvitationMapper.toPreview(requirePending(token));
    }

    /**
     * Creates the {@code users} row and already returns a session — unlike the password reset,
     * which answers 204. Whoever accepts an invitation has no other credential to use in a
     * login right afterwards: the password just chosen is the first one the account ever had.
     */
    @Transactional
    public AuthResponse accept(InvitationAcceptRequest request) {
        Invitation invitation = requirePending(request.getToken());
        // Race with a direct sign-up on the same address between the invitation and the
        // acceptance. The global uniqueness of users.email would refuse it either way, with a
        // raw violation.
        if (userRepository.existsByEmail(invitation.getEmail())) {
            throw new ConflictException(EMAIL_TAKEN);
        }

        Company company = invitation.getCompany();
        User user = new User(company, invitation.getName(), invitation.getEmail(),
                passwordEncoder.encode(request.getPassword()), invitation.getRole());
        userRepository.save(user);
        invitation.accept(Instant.now());
        invitationRepository.save(invitation);

        auditService.record(AuditEntry.ofActor(company.getId(), user.getId(), user.getEmail(),
                AuditAction.REGISTER, "User", user.getId()));
        log.info("Convite {} aceito; usuário {} criado na empresa {}", invitation.getId(),
                user.getId(), company.getId());
        return authService.startSession(user);
    }

    /**
     * Re-inviting the same address replaces the live invitation instead of piling up: the
     * partial unique index of V7 accepts a single PENDING per e-mail.
     *
     * The revocation is flushed by hand because Hibernate's flush runs inserts before updates —
     * the new invitation would reach the index before the old one stopped being PENDING.
     */
    private void replacePendingInvitation(AuthenticatedUser current, String email) {
        Optional<Invitation> pending =
                invitationRepository.findByEmailAndStatus(email, InvitationStatus.PENDING);
        if (!pending.isPresent()) {
            return;
        }
        Invitation existing = pending.get();
        if (!existing.getCompany().getId().equals(current.getCompanyId())) {
            throw new ConflictException(EMAIL_TAKEN);
        }
        existing.setStatus(InvitationStatus.REVOKED);
        invitationRepository.saveAndFlush(existing);
    }

    private Invitation requirePending(String token) {
        Invitation invitation = invitationRepository.findByTokenHash(SecretTokens.hash(token))
                .orElseThrow(() -> new BadRequestException(INVALID_INVITATION));
        if (!invitation.isPending() || invitation.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException(INVALID_INVITATION);
        }
        return invitation;
    }

    private Map<String, Object> snapshot(Invitation invitation) {
        Map<String, Object> values = AuditEntry.values();
        values.put("name", invitation.getName());
        values.put("email", invitation.getEmail());
        values.put("role", invitation.getRole().name());
        values.put("status", invitation.getStatus().name());
        values.put("expiresAt", invitation.getExpiresAt());
        return values;
    }

    private String acceptLink(String plaintext) {
        return mailerProperties.getAppBaseUrl() + "/accept-invitation?token=" + encode(plaintext);
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("UTF-8 indisponível nesta JVM", ex);
        }
    }
}
