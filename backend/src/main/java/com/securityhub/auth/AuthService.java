package com.securityhub.auth;

import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.auth.dto.AuthResponse;
import com.securityhub.auth.dto.LoginRequest;
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
        return buildResponse(admin);
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
            auditService.record(AuditEntry.ofActor(user.getCompany().getId(), user.getId(), user.getEmail(),
                    AuditAction.LOGIN_FAILED, "User", user.getId()));
            throw new UnauthorizedException("Credenciais inválidas");
        }

        user.setLastLoginAt(Instant.now());
        auditService.record(AuditEntry.ofActor(user.getCompany().getId(), user.getId(), user.getEmail(),
                AuditAction.LOGIN, "User", user.getId()));
        return buildResponse(user);
    }

    @Transactional(readOnly = true)
    public User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Sessão inválida"));
    }

    private AuthResponse buildResponse(User user) {
        String token = jwtService.generateAccessToken(user);
        return new AuthResponse(token, null, "Bearer",
                jwtService.accessTokenTtl().getSeconds(), UserMapper.toResponse(user));
    }
}
