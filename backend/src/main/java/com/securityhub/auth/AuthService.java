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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

    /**
     * A rise in {@code outcome="failure"} is credential stuffing. It is the metric a security
     * product cannot do without: the LOGIN_FAILED of the audit trail proves what happened
     * afterwards, but it does not raise an alert while it is happening.
     *
     * <p>A rule that holds for all three meters of this application: <strong>never companyId
     * nor userId as a tag</strong>. Tag cardinality multiplies the number of time series, and
     * a tenant tag with no upper bound is the classic way to bring a Prometheus down — every
     * new company creates a new series, forever. Per-tenant attribution is exactly what the
     * audit trail does, with a name, an actor and a timestamp.
     */
    private static final String LOGIN_METER = "securityhub.auth.login";

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final RefreshTokenService refreshTokenService;
    private final MeterRegistry meterRegistry;

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
            countLogin("failure");
            throw new UnauthorizedException("Credenciais inválidas");
        }

        User user = found.get();
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash()) || !user.isActive()) {
            auditService.recordIndependently(AuditEntry.ofActor(user.getCompany().getId(), user.getId(),
                    user.getEmail(), AuditAction.LOGIN_FAILED, "User", user.getId()));
            countLogin("failure");
            throw new UnauthorizedException("Credenciais inválidas");
        }

        user.setLastLoginAt(Instant.now());
        // Lazy collection of this person's expired sessions. The login is the only moment at
        // which they are guaranteed to be here and at which one extra row in the plan goes
        // unnoticed; the alternative would be a scheduler starting up inside every test context
        // (ADR 0006).
        refreshTokenService.purgeExpiredFor(user.getId());
        auditService.record(AuditEntry.ofActor(user.getCompany().getId(), user.getId(), user.getEmail(),
                AuditAction.LOGIN, "User", user.getId()));
        countLogin("success");
        return buildResponse(user, refreshTokenService.issue(user));
    }

    private void countLogin(String outcome) {
        Counter.builder(LOGIN_METER)
                .description("Login attempts by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Deliberately without {@code @Transactional}. The rotation commits its own effects before
     * refusing — the revocation of the family on a reuse and the audit row for it — and an
     * outer transaction would drag that refusal into the rollback, undoing the defence.
     *
     * There is no audit entry on a successful refresh: it would be one row per hour per session
     * of noise about a fact the corresponding LOGIN has already recorded.
     */
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(request.getRefreshToken());
        User user = userRepository.findWithCompanyById(rotation.getUserId())
                .orElseThrow(() -> new UnauthorizedException(RefreshTokenService.INVALID_SESSION));
        return buildResponse(user, rotation.getRefreshToken());
    }

    /** Always 204, including for a token that does not exist: see {@code RefreshTokenService}. */
    public void logout(RefreshTokenRequest request) {
        refreshTokenService.logout(request.getRefreshToken());
    }

    /**
     * Issues the first session of a freshly created account. Public so that invitation acceptance
     * — which creates the users row in another package — returns exactly the same AuthResponse
     * as the login, assembled by a single place.
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
     * {@code AuthResponse} did not change shape: the refreshToken field already existed and came
     * back null, omitted from the serialization by the global {@code non_null}.
     */
    private AuthResponse buildResponse(User user, String refreshToken) {
        String token = jwtService.generateAccessToken(user);
        return new AuthResponse(token, refreshToken, "Bearer",
                jwtService.accessTokenTtl().getSeconds(), UserMapper.toResponse(user));
    }
}
