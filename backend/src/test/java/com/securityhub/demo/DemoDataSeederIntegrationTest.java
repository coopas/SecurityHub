package com.securityhub.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.securityhub.asset.Asset;
import com.securityhub.asset.AssetRepository;
import com.securityhub.asset.AssetType;
import com.securityhub.asset.Criticality;
import com.securityhub.asset.Environment;
import com.securityhub.audit.AuditLogRepository;
import com.securityhub.comment.CommentRepository;
import com.securityhub.company.Company;
import com.securityhub.company.CompanyRepository;
import com.securityhub.project.Project;
import com.securityhub.project.ProjectRepository;
import com.securityhub.project.ProjectStatus;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import com.securityhub.vulnerability.Severity;
import com.securityhub.vulnerability.Vulnerability;
import com.securityhub.vulnerability.VulnerabilityRepository;
import com.securityhub.vulnerability.VulnerabilityStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The {@code demo} profile is not active in the test suite, and activating it here would
 * seed every other integration test's database. Instead of profile gymnastics, the seeder is
 * constructed by hand against the real repositories and run twice, which is the behaviour
 * that actually matters: a second {@code docker compose up} on an existing volume must not
 * duplicate anything and must not fail.
 *
 * <p>Note that a hand-built instance has no transactional proxy, so the production
 * "everything in one transaction" boundary is absent here. That is deliberate: it makes the
 * idempotency guard carry the test on its own, without a rollback hiding a missing check.
 */
class DemoDataSeederIntegrationTest extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Demo@SecurityHub2026";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private VulnerabilityRepository vulnerabilityRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private DemoDataSeeder seeder(boolean seedEnabled) {
        DemoProperties properties = new DemoProperties();
        properties.setSeedEnabled(seedEnabled);
        properties.setPassword(TEST_PASSWORD);
        return new DemoDataSeeder(properties, passwordEncoder, companyRepository, userRepository,
                projectRepository, assetRepository, vulnerabilityRepository, commentRepository);
    }

    @Test
    @DisplayName("semeia os dois tenants e é idempotente: a segunda execução não cria nem falha")
    void seedsBothTenantsAndIsIdempotent() {
        DemoDataSeeder seeder = seeder(true);

        seeder.run(null);

        assertThat(companyRepository.count()).isEqualTo(2);
        assertThat(userRepository.count()).isEqualTo(5);
        assertThat(projectRepository.count()).isEqualTo(4);
        assertThat(assetRepository.count()).isEqualTo(10);
        assertThat(vulnerabilityRepository.count()).isEqualTo(30);
        assertThat(commentRepository.count()).isEqualTo(6);
        // The seed must not fabricate history: the /audit screen belongs to what the visitor
        // does, not to the bootstrap.
        assertThat(auditLogRepository.count()).isZero();

        // Byte-identical counts after a second run, and no exception: the guard, not a
        // rollback, is what makes this true.
        assertThatCode(() -> seeder.run(null)).doesNotThrowAnyException();

        assertThat(companyRepository.count()).isEqualTo(2);
        assertThat(userRepository.count()).isEqualTo(5);
        assertThat(projectRepository.count()).isEqualTo(4);
        assertThat(assetRepository.count()).isEqualTo(10);
        assertThat(vulnerabilityRepository.count()).isEqualTo(30);
        assertThat(commentRepository.count()).isEqualTo(6);
        assertThat(auditLogRepository.count()).isZero();
    }

    @Test
    @DisplayName("não escreve nada quando securityhub.demo.seed-enabled é falso")
    void doesNothingWhenDisabled() {
        seeder(false).run(null);

        assertThat(companyRepository.count()).isZero();
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("o tenant demo tem uma conta por papel, todas com a senha da demonstração")
    void demoTenantHasOneAccountPerRole() {
        seeder(true).run(null);

        Company demo = demoCompany();
        assertThat(demo.getName()).isEqualTo("Demo Security");

        assertThat(usersOf(demo))
                .extracting(User::getEmail)
                .containsExactlyInAnyOrder("admin@demo.test", "analyst@demo.test",
                        "developer@demo.test", "viewer@demo.test");
        assertThat(usersOf(demo))
                .extracting(User::getRole)
                .containsExactlyInAnyOrder(Role.ADMIN, Role.ANALYST, Role.DEVELOPER, Role.VIEWER);

        // Every account is usable with the single documented credential, hashed by the real
        // BCrypt encoder of SecurityConfig.
        assertThat(usersOf(demo)).allSatisfy(user -> {
            assertThat(user.isActive()).isTrue();
            assertThat(user.getEmail()).isEqualTo(user.getEmail().toLowerCase());
            assertThat(passwordEncoder.matches(TEST_PASSWORD, user.getPasswordHash())).isTrue();
            assertThat(user.getPasswordHash()).doesNotContain(TEST_PASSWORD);
        });
    }

    @Test
    @DisplayName("os projetos e ativos do tenant demo cobrem todos os valores filtráveis")
    void demoTenantCoversEveryFilterableValue() {
        seeder(true).run(null);

        Company demo = demoCompany();

        List<Project> projects = projectRepository.findAll().stream()
                .filter(p -> p.getCompany().getId().equals(demo.getId()))
                .collect(Collectors.toList());
        assertThat(projects).hasSize(3);
        // Both project statuses exist, so the status filter never has a dead option.
        assertThat(projects).extracting(Project::getStatus)
                .contains(ProjectStatus.ACTIVE, ProjectStatus.ARCHIVED);

        List<Asset> assets = assetsOf(demo);
        assertThat(assets).hasSize(8);
        // No filter on /assets may return an empty list on a fresh demo.
        assertThat(assets).extracting(Asset::getType).contains(AssetType.values());
        assertThat(assets).extracting(Asset::getEnvironment).contains(Environment.values());
        assertThat(assets).extracting(Asset::getCriticality).contains(Criticality.values());
    }

    @Test
    @DisplayName("o backlog do tenant demo tem forma: atrasadas, resolvidas e todas as severidades")
    void demoBacklogIsUseful() {
        seeder(true).run(null);

        Company demo = demoCompany();
        List<Vulnerability> vulnerabilities = vulnerabilitiesOf(demo);
        assertThat(vulnerabilities).hasSize(24);

        assertThat(vulnerabilities).extracting(Vulnerability::getSeverity).contains(Severity.values());
        assertThat(vulnerabilities).extracting(Vulnerability::getStatus).contains(VulnerabilityStatus.values());

        Instant now = Instant.now();
        assertThat(vulnerabilities.stream().filter(v -> v.isOverdue(now)))
                .as("o card de atrasadas precisa de linhas")
                .hasSizeGreaterThanOrEqualTo(5);

        List<Vulnerability> resolved = vulnerabilities.stream()
                .filter(v -> v.getStatus() == VulnerabilityStatus.RESOLVED)
                .collect(Collectors.toList());
        assertThat(resolved).hasSizeGreaterThanOrEqualTo(6);
        assertThat(resolved).allSatisfy(v -> assertThat(v.getResolvedAt()).isNotNull());

        // The CHECK of V5 expressed from the other side: only RESOLVED carries a resolvedAt.
        assertThat(vulnerabilities)
                .allSatisfy(v -> assertThat(v.getResolvedAt() != null)
                        .isEqualTo(v.getStatus() == VulnerabilityStatus.RESOLVED));

        // Relative dates, not literals: nothing was discovered in the future and the series
        // that feeds the trend chart spans roughly the last month.
        assertThat(vulnerabilities).allSatisfy(v -> assertThat(v.getDiscoveredAt()).isBefore(now));
        Instant oldest = vulnerabilities.stream().map(Vulnerability::getDiscoveredAt)
                .min(Instant::compareTo).orElseThrow(AssertionError::new);
        assertThat(oldest).isAfter(now.minusSeconds(40L * 24 * 3600));

        // anyMatch and not anySatisfy: a failing anySatisfy renders the offending entity, which
        // would touch the lazy `assignedTo` proxy outside a session.
        assertThat(vulnerabilities).anyMatch(v -> v.getCve() != null);
        assertThat(vulnerabilities).anyMatch(v -> v.getCve() == null);
        assertThat(vulnerabilities).anyMatch(v -> v.getCvssScore() != null);
        assertThat(vulnerabilities).anyMatch(v -> v.getAssignedTo() != null);
        assertThat(vulnerabilities).anyMatch(v -> v.getAssignedTo() == null);
    }

    @Test
    @DisplayName("o segundo tenant é pequeno e completamente isolado do primeiro")
    void northwindTenantIsIsolated() {
        seeder(true).run(null);

        Company demo = demoCompany();
        Company northwind = companyBySlug(DemoDataSeeder.NORTHWIND_SLUG);
        assertThat(northwind.getId()).isNotEqualTo(demo.getId());

        assertThat(usersOf(northwind)).extracting(User::getEmail)
                .containsExactly("admin@northwind.test");
        assertThat(assetsOf(northwind)).hasSize(2);
        assertThat(vulnerabilitiesOf(northwind)).hasSize(6);

        // Nothing crosses the tenant boundary: no asset of one company hangs off a project of
        // the other, and no finding of one company points at an asset of the other.
        Set<Long> northwindProjects = projectIdsOf(northwind);
        Set<Long> northwindAssets = assetsOf(northwind).stream()
                .map(a -> a.getId()).collect(Collectors.toSet());
        assertThat(assetsOf(northwind))
                .allSatisfy(a -> assertThat(northwindProjects).contains(a.getProject().getId()));
        assertThat(vulnerabilitiesOf(northwind))
                .allSatisfy(v -> assertThat(northwindAssets).contains(v.getAsset().getId()));

        Set<Long> demoAssets = assetsOf(demo).stream().map(a -> a.getId()).collect(Collectors.toSet());
        assertThat(vulnerabilitiesOf(demo))
                .allSatisfy(v -> assertThat(demoAssets).contains(v.getAsset().getId()));
        assertThat(northwindAssets).doesNotContainAnyElementsOf(demoAssets);

        // Every seeded comment belongs to the first tenant; the second has none.
        assertThat(commentRepository.findAll())
                .allSatisfy(c -> assertThat(c.getCompany().getId()).isEqualTo(demo.getId()));
    }

    /**
     * One line that proves the seeder can never fire outside the {@code demo} profile: this
     * context runs on {@code test}, and the bean simply does not exist in it.
     */
    @Test
    @DisplayName("o bean do seeder não existe fora do perfil demo")
    void seederBeanIsAbsentOutsideDemoProfile() {
        assertThat(applicationContext.getBeanNamesForType(DemoDataSeeder.class)).isEmpty();
    }

    private Company demoCompany() {
        return companyBySlug(DemoDataSeeder.DEMO_SLUG);
    }

    private Company companyBySlug(String slug) {
        return companyRepository.findAll().stream()
                .filter(c -> slug.equals(c.getSlug()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("empresa '" + slug + "' não foi semeada"));
    }

    private List<User> usersOf(Company company) {
        return userRepository.findAll().stream()
                .filter(u -> u.getCompany().getId().equals(company.getId()))
                .collect(Collectors.toList());
    }

    private List<Asset> assetsOf(Company company) {
        return assetRepository.findAll().stream()
                .filter(a -> a.getCompany().getId().equals(company.getId()))
                .collect(Collectors.toList());
    }

    private Set<Long> projectIdsOf(Company company) {
        return projectRepository.findAll().stream()
                .filter(p -> p.getCompany().getId().equals(company.getId()))
                .map(Project::getId)
                .collect(Collectors.toSet());
    }

    private List<Vulnerability> vulnerabilitiesOf(Company company) {
        return vulnerabilityRepository.findAll().stream()
                .filter(v -> v.getCompany().getId().equals(company.getId()))
                .collect(Collectors.toList());
    }
}
