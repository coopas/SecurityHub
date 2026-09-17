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
 * Redefinição de senha por link de uso único.
 *
 * As duas pontas são deliberadamente cegas: o pedido responde 202 para qualquer endereço e a
 * confirmação responde o mesmo 400 para token desconhecido, vencido ou de conta desativada.
 * Um visitante anônimo em uma página pública não pode descobrir quem tem conta aqui.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    /**
     * 400 e não 401. O 401 é o sinal que o interceptor do frontend traduz como "sua sessão
     * expirou": ele limparia o armazenamento local e redirecionaria para o login. Quem está
     * nesta tela é anônimo em uma página pública, não tem sessão nenhuma para expirar, e
     * receberia um redirecionamento no lugar da mensagem que explica o que aconteceu.
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
     * Sempre 202, byte a byte igual, conhecido ou não.
     *
     * Não há BCrypt falso aqui, ao contrário de {@code AuthService.login}: naquele caminho a
     * verificação da senha é o trabalho caro que precisa acontecer também no caso do e-mail
     * desconhecido, senão o tempo de resposta entrega a diferença. Aqui não se verifica senha
     * nenhuma — o trabalho caro seria a ida ao SMTP, e ela sai da thread da requisição
     * (ADR 0007), de modo que os dois caminhos gastam uma consulta indexada e nada mais.
     */
    @Transactional
    public void request(PasswordResetRequest request) {
        String email = User.normalizeEmail(request.getEmail());
        Optional<User> found = userRepository.findByEmailAndActiveTrue(email);
        if (!found.isPresent()) {
            // Sem o endereço no log: a trilha de um sistema multi-tenant não é lugar para
            // registrar quem tentou existir.
            log.info("Pedido de redefinição para endereço desconhecido ou inativo, ignorado");
            return;
        }

        User user = found.get();
        passwordResetTokenRepository.deleteByUserId(user.getId());

        String plaintext = SecretTokens.random();
        Instant now = Instant.now();
        passwordResetTokenRepository.save(new PasswordResetToken(user, SecretTokens.hash(plaintext),
                now.plus(TOKEN_TTL)));

        // Independente porque a linha de users que ela referencia já está comitada e o fato
        // "alguém pediu redefinição" tem valor mesmo que algo adiante desfaça a transação.
        auditService.recordIndependently(AuditEntry.ofActor(user.getCompany().getId(), user.getId(),
                user.getEmail(), AuditAction.PASSWORD_RESET, ENTITY_TYPE, user.getId()));

        mailer.sendAfterCommit(user.getEmail(), MailTemplates.PASSWORD_RESET_SUBJECT,
                MailTemplates.passwordReset(user.getName(), resetLink(plaintext),
                        TOKEN_TTL.toMinutes()));
        log.info("Link de redefinição emitido para o usuário {}", user.getId());
    }

    /**
     * Devolve 204 e não uma sessão, ao contrário do aceite de convite. A assimetria é
     * proposital: quem aceita um convite provou a posse do e-mail para criar a conta e não tem
     * outra credencial para usar, enquanto quem redefine a senha acabou de escolher uma e o
     * caminho honesto é ir ao login com ela. Emitir sessão aqui faria de um link de e-mail
     * interceptado uma sessão pronta, sem nenhum passo a mais.
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
        // Uso único: a linha some em vez de ganhar um estado. lastLoginAt fica intocado —
        // redefinir senha não é entrar.
        passwordResetTokenRepository.delete(token);
        refreshTokenService.revokeAllForUser(user.getId(), RevocationReason.PASSWORD_RESET);

        auditService.record(AuditEntry.ofActor(user.getCompany().getId(), user.getId(),
                user.getEmail(), AuditAction.PASSWORD_RESET, ENTITY_TYPE, user.getId()));
        log.info("Senha redefinida para o usuário {}", user.getId());
    }

    /**
     * O link aponta para o frontend, não para a API: o destinatário precisa de uma tela onde
     * digitar a senha nova.
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
