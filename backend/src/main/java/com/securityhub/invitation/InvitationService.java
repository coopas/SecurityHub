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
 * Convites para entrar em uma empresa. As duas pontas do aceite são públicas: quem clica no
 * link ainda não tem conta, então a posse do token é a única credencial possível.
 *
 * A autorização das operações administrativas fica aqui e não no controller, como no resto do
 * projeto (docs/permissions.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvitationService {

    static final Duration TOKEN_TTL = Duration.ofDays(7);
    static final String ENTITY_TYPE = "Invitation";

    /**
     * A mesma mensagem de {@code AuthService.register}. Ela cobre três casos que o convidante
     * não pode distinguir: o endereço já tem conta nesta empresa, tem conta em outra, ou tem
     * um convite vivo em outra. Uma mensagem específica para cada um diria a um administrador
     * curioso em que outra empresa do produto aquele e-mail aparece.
     */
    static final String EMAIL_TAKEN = "E-mail já cadastrado";

    /** Desconhecido, vencido, revogado e já aceito são o mesmo 400 para quem apresenta. */
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
        // Mapeado aqui dentro: open-in-view está desligado e company/invitedBy são lazy.
        return InvitationMapper.toResponse(invitation);
    }

    /**
     * Array puro e sem paginação, como {@code GET /users}: são poucas linhas por empresa e a
     * tela de administração as mostra de uma vez.
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
            // Além de não fazer sentido, revogar um convite aceito violaria a equivalência
            // entre status ACCEPTED e accepted_at que V7 verifica.
            throw new ConflictException("Convite já foi aceito ou revogado");
        }

        Map<String, Object> before = snapshot(invitation);
        invitation.setStatus(InvitationStatus.REVOKED);
        invitationRepository.save(invitation);

        auditService.record(AuditEntry.updated(current, ENTITY_TYPE, id, before, snapshot(invitation)));
        log.info("Convite {} revogado na empresa {}", id, current.getCompanyId());
    }

    /** Prévia pública: quem chega pelo link precisa saber para onde está sendo convidado. */
    @Transactional(readOnly = true)
    public InvitationPreviewResponse preview(String token) {
        return InvitationMapper.toPreview(requirePending(token));
    }

    /**
     * Cria a linha de {@code users} e já devolve uma sessão — ao contrário da redefinição de
     * senha, que responde 204. Quem aceita um convite não tem outra credencial para usar em um
     * login logo depois: a senha que acabou de escolher é a primeira que a conta teve.
     */
    @Transactional
    public AuthResponse accept(InvitationAcceptRequest request) {
        Invitation invitation = requirePending(request.getToken());
        // Corrida com um cadastro direto no mesmo endereço entre o convite e o aceite. A
        // unicidade global de users.email recusaria de qualquer forma, com uma violação crua.
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
     * Reconvidar o mesmo endereço substitui o convite vivo em vez de acumular: o índice único
     * parcial de V7 aceita um só PENDING por e-mail.
     *
     * A revogação é descarregada na mão porque o flush do Hibernate executa inserts antes de
     * updates — o convite novo chegaria ao índice antes de o antigo deixar de ser PENDING.
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
