package com.securityhub.auth;

import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.auth.dto.AuthResponse;
import com.securityhub.auth.dto.LoginRequest;
import com.securityhub.auth.dto.RefreshTokenRequest;
import com.securityhub.auth.dto.RegisterRequest;
import com.securityhub.company.Company;
import com.securityhub.company.CompanyRepository;
import com.securityhub.company.SlugGenerator;
import com.securityhub.security.JwtService;
import com.securityhub.shared.error.ConflictException;
import com.securityhub.shared.error.UnauthorizedException;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.user.UserMapper;
import com.securityhub.user.dto.UserResponse;
import com.securityhub.user.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final RefreshTokenService refreshTokenService;

    /**
     * Hash of an unused random password. Verifying an incoming password against it when the
     * e-mail is unknown keeps the failed-login timing comparable to a wrong password, so the
     * endpoint cannot be used to enumerate accounts.
     */
    private String dummyHash;

    @PostConstruct
    void init() {
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("E-mail já cadastrado");
        }

        Company company = new Company(request.getCompanyName().trim(),
                SlugGenerator.uniqueSlug(request.getCompanyName(), companyRepository::existsBySlug));
        companyRepository.save(company);

        User admin = new User(company, request.getName().trim(), email,
                passwordEncoder.encode(request.getPassword()), Role.ADMIN);
        userRepository.save(admin);

        auditService.record(AuditEntry.ofActor(company.getId(), admin.getId(), admin.getEmail(),
                AuditAction.REGISTER, "Company", company.getId()));
        log.info("Empresa {} criada com administrador {}", company.getId(), admin.getId());
        return buildResponse(admin, refreshTokenService.issue(admin));
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = User.normalizeEmail(request.getEmail());
        Optional<User> found = userRepository.findByEmail(email);

        if (!found.isPresent()) {
            passwordEncoder.matches(request.getPassword(), dummyHash);
            throw new UnauthorizedException("Credenciais inválidas");
        }

        User user = found.get();
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash()) || !user.isActive()) {
            auditService.recordIndependently(AuditEntry.ofActor(user.getCompany().getId(), user.getId(),
                    user.getEmail(), AuditAction.LOGIN_FAILED, "User", user.getId()));
            throw new UnauthorizedException("Credenciais inválidas");
        }

        user.setLastLoginAt(Instant.now());
        // Coleta preguiçosa das sessões vencidas desta pessoa. O login é o único momento em
        // que ela está garantidamente aqui e em que uma linha a mais no plano não é percebida;
        // a alternativa seria um agendador subindo dentro de todo contexto de teste (ADR 0006).
        refreshTokenService.purgeExpiredFor(user.getId());
        auditService.record(AuditEntry.ofActor(user.getCompany().getId(), user.getId(), user.getEmail(),
                AuditAction.LOGIN, "User", user.getId()));
        return buildResponse(user, refreshTokenService.issue(user));
    }

    /**
     * Deliberadamente sem {@code @Transactional}. A rotação comita os próprios efeitos antes
     * de recusar — a revogação da família em um reuso e a linha de auditoria dela — e uma
     * transação externa arrastaria essa recusa para o rollback, desfazendo a defesa.
     *
     * Não há auditoria no refresh bem-sucedido: seria uma linha por hora por sessão de ruído
     * sobre um fato que o LOGIN correspondente já registrou.
     */
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(request.getRefreshToken());
        User user = userRepository.findWithCompanyById(rotation.getUserId())
                .orElseThrow(() -> new UnauthorizedException(RefreshTokenService.INVALID_SESSION));
        return buildResponse(user, rotation.getRefreshToken());
    }

    /** 204 sempre, inclusive para um token que não existe: ver {@code RefreshTokenService}. */
    public void logout(RefreshTokenRequest request) {
        refreshTokenService.logout(request.getRefreshToken());
    }

    /**
     * Emite a primeira sessão de uma conta recém-criada. Pública para que o aceite de convite
     * — que cria a linha de users em outro pacote — devolva exatamente a mesma AuthResponse do
     * login, montada por um único lugar.
     */
    public AuthResponse startSession(User user) {
        return buildResponse(user, refreshTokenService.issue(user));
    }

    /**
     * Returns the DTO rather than the entity: {@code open-in-view} is disabled, so mapping
     * after the transaction closes would fail on the lazy company association.
     */
    @Transactional(readOnly = true)
    public UserResponse currentUser(Long userId) {
        return userRepository.findById(userId)
                .map(UserMapper::toResponse)
                .orElseThrow(() -> new UnauthorizedException("Sessão inválida"));
    }

    /**
     * {@code AuthResponse} não mudou de forma: o campo refreshToken já existia e vinha nulo,
     * omitido da serialização pelo {@code non_null} global.
     */
    private AuthResponse buildResponse(User user, String refreshToken) {
        String token = jwtService.generateAccessToken(user);
        return new AuthResponse(token, refreshToken, "Bearer",
                jwtService.accessTokenTtl().getSeconds(), UserMapper.toResponse(user));
    }
}
