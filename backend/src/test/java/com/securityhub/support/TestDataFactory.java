package com.securityhub.support;

import com.securityhub.asset.Asset;
import com.securityhub.asset.AssetRepository;
import com.securityhub.asset.AssetType;
import com.securityhub.asset.Criticality;
import com.securityhub.asset.Environment;
import com.securityhub.company.Company;
import com.securityhub.company.CompanyRepository;
import com.securityhub.project.Project;
import com.securityhub.project.ProjectRepository;
import com.securityhub.project.ProjectStatus;
import com.securityhub.security.JwtService;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import com.securityhub.vulnerability.Severity;
import com.securityhub.vulnerability.Vulnerability;
import com.securityhub.vulnerability.VulnerabilityRepository;
import com.securityhub.vulnerability.VulnerabilityStatus;
import java.time.Instant;
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
    private final ProjectRepository projectRepository;
    private final AssetRepository assetRepository;
    private final VulnerabilityRepository vulnerabilityRepository;

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

    // --- domain fixtures -----------------------------------------------------
    //
    // Written through the repositories and not through the API on purpose. The status rule
    // of VulnerabilityService owns resolvedAt — it stamps Instant.now() on entering RESOLVED
    // and clears it on leaving — so a dataset that needs a resolution on a specific past day
    // cannot be built through PATCH /status. Going through the repository is also what keeps
    // an aggregation test independent of the write endpoints it is not testing.

    @Transactional
    public Project project(Company company, String name) {
        return projectRepository.save(new Project(company, name, null, ProjectStatus.ACTIVE, null));
    }

    /**
     * The parent is reloaded inside this transaction so {@code getCompany()} hands back a
     * lazy proxy attached to the current session; a proxy carried over from the previous
     * transaction would already be detached.
     */
    @Transactional
    public Asset asset(Project project, String name) {
        Project managed = projectRepository.findById(project.getId())
                .orElseThrow(() -> new IllegalStateException("Projeto de teste não encontrado"));
        return assetRepository.save(new Asset(managed.getCompany(), managed, name, null,
                AssetType.SERVER, null, Environment.PRODUCTION, Criticality.MEDIUM));
    }

    /**
     * Full control over every date, which is what a deterministic dashboard dataset needs.
     *
     * @param resolvedAt must be non-null exactly when the status is RESOLVED; V5 enforces the
     *                   same equivalence with a CHECK constraint, and failing here instead
     *                   gives a readable message at the line that built the row.
     */
    @Transactional
    public Vulnerability vulnerability(Asset asset, String title, Severity severity,
                                       VulnerabilityStatus status, Instant discoveredAt,
                                       Instant dueDate, Instant resolvedAt) {
        if ((status == VulnerabilityStatus.RESOLVED) != (resolvedAt != null)) {
            throw new IllegalArgumentException(
                    "resolvedAt deve existir exatamente quando o status for RESOLVED: "
                            + status + " / " + resolvedAt);
        }
        Asset managed = assetRepository.findById(asset.getId())
                .orElseThrow(() -> new IllegalStateException("Ativo de teste não encontrado"));
        Vulnerability vulnerability = new Vulnerability(managed.getCompany(), managed, title, null, severity,
                null, null, discoveredAt, dueDate, null, null);
        vulnerability.setStatus(status);
        vulnerability.setResolvedAt(resolvedAt);
        return vulnerabilityRepository.save(vulnerability);
    }

    /** Shorthand for a still-open finding with no due date. */
    @Transactional
    public Vulnerability openVulnerability(Asset asset, String title, Severity severity, Instant discoveredAt) {
        return vulnerability(asset, title, severity, VulnerabilityStatus.OPEN, discoveredAt, null, null);
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
