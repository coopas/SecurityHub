package com.securityhub.support;

import com.securityhub.company.Company;
import com.securityhub.company.CompanyRepository;
import com.securityhub.security.JwtService;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds real persisted tenants for integration tests. Tests must never fabricate a token
 * by hand: going through JwtService is what proves the production path accepts it.
 */
@RequiredArgsConstructor
public class TestDataFactory {

    public static final String DEFAULT_PASSWORD = "senha-de-teste-123";

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public Company company(String name, String slug) {
        return companyRepository.save(new Company(name, slug));
    }

    @Transactional
    public User user(Company company, String email, Role role) {
        return userRepository.save(new User(company, email.split("@")[0], email,
                passwordEncoder.encode(DEFAULT_PASSWORD), role));
    }

    @Transactional
    public User inactiveUser(Company company, String email, Role role) {
        User user = user(company, email, role);
        user.setActive(false);
        return userRepository.save(user);
    }

    public String token(User user) {
        return jwtService.generateAccessToken(user);
    }

    public String bearer(User user) {
        return "Bearer " + token(user);
    }

    /** A company with one user per role, which is what most authorization tests need. */
    @Transactional
    public Tenant tenant(String slug) {
        Company company = company(slug.toUpperCase(), slug);
        return new Tenant(company,
                user(company, "admin@" + slug + ".test", Role.ADMIN),
                user(company, "analyst@" + slug + ".test", Role.ANALYST),
                user(company, "developer@" + slug + ".test", Role.DEVELOPER),
                user(company, "viewer@" + slug + ".test", Role.VIEWER));
    }

    public static class Tenant {

        public final Company company;
        public final User admin;
        public final User analyst;
        public final User developer;
        public final User viewer;

        Tenant(Company company, User admin, User analyst, User developer, User viewer) {
            this.company = company;
            this.admin = admin;
            this.analyst = analyst;
            this.developer = developer;
            this.viewer = viewer;
        }
    }
}
