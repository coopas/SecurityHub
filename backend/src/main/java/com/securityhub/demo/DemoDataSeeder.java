package com.securityhub.demo;

import com.securityhub.asset.Asset;
import com.securityhub.asset.AssetRepository;
import com.securityhub.asset.AssetType;
import com.securityhub.asset.Criticality;
import com.securityhub.asset.Environment;
import com.securityhub.comment.Comment;
import com.securityhub.comment.CommentRepository;
import com.securityhub.company.Company;
import com.securityhub.company.CompanyRepository;
import com.securityhub.project.Project;
import com.securityhub.project.ProjectRepository;
import com.securityhub.project.ProjectStatus;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import com.securityhub.vulnerability.Severity;
import com.securityhub.vulnerability.Vulnerability;
import com.securityhub.vulnerability.VulnerabilityRepository;
import com.securityhub.vulnerability.VulnerabilityStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Populates a reproducible data set the first time the application boots on the
 * {@code demo} profile. {@code docker-compose.yml} starts the backend with
 * {@code SPRING_PROFILES_ACTIVE=demo}, so this is literally what every visitor who runs
 * {@code docker compose up} sees: it is the product demo, not a test fixture.
 *
 * <p><b>Why an {@code ApplicationRunner}.</b> A Flyway repeatable migration runs in every
 * profile, cannot produce a BCrypt hash and cannot be switched off by a property; a
 * {@code data.sql} fights Flyway for ownership of the schema; a {@code @PostConstruct}
 * orders unpredictably against Flyway and the {@code EntityManagerFactory}. A runner fires
 * once, after the context is fully up, and can be gated twice.
 *
 * <p><b>Why the gate is double.</b> {@code @Profile("demo")} keeps the bean out of the prod
 * and test contexts entirely — it cannot fire there even by accident — while
 * {@code securityhub.demo.seed-enabled} lets a hosted demo that must stay on the
 * {@code demo} profile stop re-seeding without changing its active profiles.
 *
 * <p><b>Why nothing here is audited.</b> There is no actor and no HTTP request behind these
 * rows, and roughly forty bootstrap entries would bury, on the first page of the
 * {@code /audit} screen, exactly the actions the visitor generates by clicking around. No
 * fabricated "past activity" rows either: that would be inventing history. The visitor's
 * first login writes a real {@code LOGIN} entry, so the screen is never actually empty.
 *
 * <p><b>Why the repositories and not {@code AuthService.register}.</b> {@code register}
 * writes audit rows, only ever creates an {@code ADMIN} and creates one company per call.
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_SLUG = "demo";
    public static final String NORTHWIND_SLUG = "northwind";

    private final DemoProperties demoProperties;
    private final PasswordEncoder passwordEncoder;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final AssetRepository assetRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final CommentRepository commentRepository;

    /**
     * Deliberately not wrapped in a try/catch. A demo profile whose data failed to load is
     * broken, and a backend that starts anyway and serves an empty application hides that;
     * letting the exception escape stops the context, which is the honest outcome.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!demoProperties.isSeedEnabled()) {
            log.info("Demo seed desativado (securityhub.demo.seed-enabled=false); nada a fazer.");
            return;
        }
        // Single idempotency guard, and it is enough: the whole seed runs inside this one
        // @Transactional method, so a partially-seeded state cannot exist — either every row
        // of both tenants committed or none did. Without the guard, a second boot on the
        // same volume would die on the global uk_users_email constraint while inserting
        // admin@demo.test.
        if (companyRepository.existsBySlug(DEMO_SLUG)) {
            log.info("Demo seed já aplicado (empresa '{}' existe); nenhuma linha criada.", DEMO_SLUG);
            return;
        }

        Instant now = Instant.now();
        // Encoded once and shared by the five accounts. BCrypt at cost 12 costs a few hundred
        // milliseconds per call, and every seeded account has the same public demo password
        // anyway, so five separate hashes would only add a second to every cold start.
        String passwordHash = passwordEncoder.encode(demoProperties.getPassword());

        Counts counts = new Counts();
        seedDemoTenant(now, passwordHash, counts);
        seedNorthwindTenant(now, passwordHash, counts);

        // One INFO line so `docker compose logs backend` tells a visitor what exists and how
        // to get in. The password is never part of it.
        log.info("Demo seed aplicado. Empresas: {} (admin@demo.test, analyst@demo.test, "
                        + "developer@demo.test, viewer@demo.test) e {} (admin@northwind.test). "
                        + "Linhas: {} empresas, {} usuários, {} projetos, {} ativos, {} vulnerabilidades, {} comentários. "
                        + "Senha de todas as contas: variável SECURITYHUB_DEMO_PASSWORD.",
                DEMO_SLUG, NORTHWIND_SLUG,
                counts.companies, counts.users, counts.projects, counts.assets, counts.vulnerabilities, counts.comments);
    }

    // ------------------------------------------------------------------ tenant 1

    /**
     * The tenant the visitor logs into: four roles, three projects, every asset enum value
     * covered and a backlog with shape. Dates are all relative to {@code now}, never
     * literals — the dashboard series are built from {@code discoveredAt} and
     * {@code resolvedAt}, so hardcoded dates would make the trend render flat and empty for
     * every visitor one month after release.
     */
    private void seedDemoTenant(Instant now, String passwordHash, Counts counts) {
        Company company = companyRepository.save(new Company("Demo Security", DEMO_SLUG));
        counts.companies++;

        // One account per role, all sharing the same password, so the README can document a
        // single credential and a visitor switches role by e-mail alone.
        User admin = user(company, "Ana Ribeiro", "admin@demo.test", passwordHash, Role.ADMIN, counts);
        User analyst = user(company, "Bruno Carvalho", "analyst@demo.test", passwordHash, Role.ANALYST, counts);
        User developer = user(company, "Carla Mendes", "developer@demo.test", passwordHash, Role.DEVELOPER, counts);
        User viewer = user(company, "Diego Souza", "viewer@demo.test", passwordHash, Role.VIEWER, counts);

        // Three projects, one of them ARCHIVED so the status filter of /projects has both
        // values to show.
        Project payments = project(company, "Plataforma de Pagamentos",
                "Serviços de cobrança, antifraude e conciliação financeira.",
                ProjectStatus.ACTIVE, admin, counts);
        Project portal = project(company, "Portal do Cliente",
                "Aplicação web e API pública de autoatendimento.",
                ProjectStatus.ACTIVE, admin, counts);
        Project legacy = project(company, "Faturamento Legado",
                "Sistema em desativação, mantido apenas para consulta histórica.",
                ProjectStatus.ARCHIVED, admin, counts);

        // Eight assets chosen so that every AssetType, every Environment and every
        // Criticality appears at least once: no filter on /assets ever returns an empty list
        // on a fresh demo. One asset deliberately has no identifier, since it is optional.
        Asset paymentsApi = asset(company, payments, "API de Pagamentos",
                "Endpoints de cobrança expostos aos parceiros.",
                AssetType.API, "api.pagamentos.demo.test", Environment.PRODUCTION, Criticality.CRITICAL, counts);
        Asset transactionsDb = asset(company, payments, "Banco de Transações",
                "PostgreSQL com o histórico de cobranças.",
                AssetType.DATABASE, "db-transacoes.demo.internal", Environment.PRODUCTION, Criticality.HIGH, counts);
        Asset edgeGateway = asset(company, payments, "Gateway de Borda",
                "Proxy reverso que termina o TLS das APIs.",
                AssetType.SERVER, "10.20.0.11", Environment.STAGING, Criticality.MEDIUM, counts);
        Asset portalWeb = asset(company, portal, "Portal do Cliente",
                "Single page application de autoatendimento.",
                AssetType.WEBSITE, "https://portal.demo.test", Environment.PRODUCTION, Criticality.HIGH, counts);
        Asset portalApi = asset(company, portal, "API do Portal",
                "Backend do portal no ambiente de desenvolvimento.",
                AssetType.API, "api-dev.portal.demo.test", Environment.DEVELOPMENT, Criticality.LOW, counts);
        Asset supportWorkstation = asset(company, portal, "Estação do Suporte",
                "Notebook usado pela equipe de atendimento.",
                AssetType.WORKSTATION, "ws-suporte-014", Environment.TEST, Criticality.LOW, counts);
        Asset legacyServer = asset(company, legacy, "Servidor de Faturamento",
                "Máquina virtual que ainda roda o faturamento antigo.",
                AssetType.SERVER, "10.30.4.7", Environment.TEST, Criticality.MEDIUM, counts);
        Asset fileShare = asset(company, legacy, "Compartilhamento de Arquivos",
                "Volume SMB com relatórios exportados. Sem identificador definido.",
                AssetType.OTHER, null, Environment.STAGING, Criticality.LOW, counts);

        // --- Six resolved findings, resolvedAt spread over the last three weeks. The V5
        // CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL)) makes this the only
        // status allowed to carry a resolution instant.
        Vulnerability sqlInjection = vulnerability(company, paymentsApi,
                "SQL injection no endpoint de cobrança",
                "Parâmetro `reference` concatenado diretamente na consulta de cobranças.",
                Severity.CRITICAL, "9.8", "CVE-2024-21626",
                daysAgo(now, 28), daysAgo(now, 21), developer, analyst,
                VulnerabilityStatus.RESOLVED, daysAgo(now, 2), counts);
        vulnerability(company, transactionsDb,
                "Credenciais padrão no usuário de replicação",
                "O usuário `replicator` mantinha a senha de fábrica do provisionamento.",
                Severity.HIGH, "8.1", null,
                daysAgo(now, 26), daysAgo(now, 19), analyst, analyst,
                VulnerabilityStatus.RESOLVED, daysAgo(now, 5), counts);
        vulnerability(company, portalWeb,
                "XSS refletido na busca do portal",
                "O termo pesquisado retornava sem escape no HTML da página de resultados.",
                Severity.MEDIUM, "6.1", "CVE-2023-38545",
                daysAgo(now, 24), daysAgo(now, 17), developer, analyst,
                VulnerabilityStatus.RESOLVED, daysAgo(now, 9), counts);
        vulnerability(company, edgeGateway,
                "TLS 1.0 ainda habilitado no gateway",
                "Protocolos obsoletos aceitos na negociação com clientes antigos.",
                Severity.MEDIUM, "5.3", null,
                daysAgo(now, 22), daysAgo(now, 15), null, analyst,
                VulnerabilityStatus.RESOLVED, daysAgo(now, 13), counts);
        vulnerability(company, supportWorkstation,
                "Antivírus com assinaturas desatualizadas",
                "A estação estava há mais de sessenta dias sem atualizar as definições.",
                Severity.LOW, "3.1", null,
                daysAgo(now, 20), daysAgo(now, 13), developer, admin,
                VulnerabilityStatus.RESOLVED, daysAgo(now, 17), counts);
        vulnerability(company, portalApi,
                "Cabeçalho X-Powered-By exposto",
                "A stack e a versão do runtime eram devolvidas em toda resposta.",
                Severity.LOW, "2.6", null,
                daysAgo(now, 30), daysAgo(now, 16), analyst, analyst,
                VulnerabilityStatus.RESOLVED, daysAgo(now, 21), counts);

        // --- Five overdue findings: dueDate in the past with a status that is still
        // actionable, which is exactly Vulnerability.isOverdue and the partial index of V5.
        Vulnerability rateLimiting = vulnerability(company, paymentsApi,
                "Ausência de rate limiting em /payments",
                "Nenhum limite por cliente permite força bruta sobre referências de cobrança.",
                Severity.HIGH, "7.5", null,
                daysAgo(now, 27), daysAgo(now, 6), developer, analyst,
                VulnerabilityStatus.IN_PROGRESS, null, counts);
        Vulnerability backups = vulnerability(company, transactionsDb,
                "Backups sem criptografia em repouso",
                "Os dumps diários são gravados em texto claro no bucket de retenção.",
                Severity.CRITICAL, "9.1", null,
                daysAgo(now, 25), daysAgo(now, 4), analyst, admin,
                VulnerabilityStatus.OPEN, null, counts);
        vulnerability(company, portalWeb,
                "Cookie de sessão sem a flag Secure",
                "O cookie de sessão pode trafegar em uma conexão não cifrada.",
                Severity.MEDIUM, "5.4", null,
                daysAgo(now, 21), daysAgo(now, 3), null, analyst,
                VulnerabilityStatus.OPEN, null, counts);
        vulnerability(company, legacyServer,
                "Kernel sem o patch de escalonamento local",
                "A imagem do servidor legado não recebe correções desde a última janela.",
                Severity.HIGH, "8.4", "CVE-2022-0847",
                daysAgo(now, 19), daysAgo(now, 2), developer, analyst,
                VulnerabilityStatus.IN_PROGRESS, null, counts);
        vulnerability(company, fileShare,
                "Compartilhamento com permissão anônima",
                "O volume aceita leitura sem autenticação a partir da rede interna.",
                Severity.MEDIUM, "6.5", null,
                daysAgo(now, 18), daysAgo(now, 1), null, analyst,
                VulnerabilityStatus.OPEN, null, counts);

        // --- Open, still inside the deadline (or without one).
        vulnerability(company, paymentsApi,
                "Log de auditoria sem retenção definida",
                "Não há política de expurgo nem de arquivamento para os registros.",
                Severity.LOW, "3.7", null,
                daysAgo(now, 16), daysAhead(now, 10), null, analyst,
                VulnerabilityStatus.OPEN, null, counts);
        vulnerability(company, transactionsDb,
                "Consulta sem índice permite negação de serviço",
                "O relatório de conciliação faz varredura completa da tabela de transações.",
                Severity.MEDIUM, "6.8", null,
                daysAgo(now, 14), daysAhead(now, 14), analyst, analyst,
                VulnerabilityStatus.OPEN, null, counts);
        vulnerability(company, portalWeb,
                "Content-Security-Policy ausente",
                "Sem CSP, qualquer injeção de script executa com os privilégios da página.",
                Severity.MEDIUM, "5.9", null,
                daysAgo(now, 12), daysAhead(now, 7), null, analyst,
                VulnerabilityStatus.OPEN, null, counts);
        vulnerability(company, portalApi,
                "Dependência com vulnerabilidade conhecida",
                "Biblioteca de log em versão afetada por execução remota de código.",
                Severity.HIGH, "7.8", "CVE-2021-44228",
                daysAgo(now, 11), daysAhead(now, 5), developer, analyst,
                VulnerabilityStatus.OPEN, null, counts);
        vulnerability(company, paymentsApi,
                "Chaves de API sem rotação",
                "As credenciais dos parceiros nunca expiram e não há processo de troca.",
                Severity.HIGH, "7.2", null,
                daysAgo(now, 9), null, null, admin,
                VulnerabilityStatus.OPEN, null, counts);
        vulnerability(company, edgeGateway,
                "Console administrativo exposto na VPN",
                "A interface de administração responde a qualquer host da rede interna.",
                Severity.CRITICAL, "9.3", null,
                daysAgo(now, 7), daysAhead(now, 2), analyst, analyst,
                VulnerabilityStatus.OPEN, null, counts);

        // --- In progress, still inside the deadline.
        vulnerability(company, supportWorkstation,
                "Disco sem criptografia completa",
                "O notebook de suporte guarda exportações de clientes sem cifragem.",
                Severity.MEDIUM, "6.2", null,
                daysAgo(now, 6), daysAhead(now, 12), developer, admin,
                VulnerabilityStatus.IN_PROGRESS, null, counts);
        vulnerability(company, portalWeb,
                "Enumeração de usuários na tela de login",
                "Mensagens distintas revelam se o e-mail informado existe.",
                Severity.LOW, "3.9", null,
                daysAgo(now, 5), daysAhead(now, 20), analyst, analyst,
                VulnerabilityStatus.IN_PROGRESS, null, counts);
        vulnerability(company, transactionsDb,
                "Usuário da aplicação com privilégio de superusuário",
                "A aplicação conecta com um papel que pode alterar o esquema.",
                Severity.CRITICAL, "9.0", "CVE-2024-3094",
                daysAgo(now, 4), daysAhead(now, 3), developer, analyst,
                VulnerabilityStatus.IN_PROGRESS, null, counts);

        // --- Accepted risk. The first one has a due date in the past on purpose: an accepted
        // finding is never "overdue", which is what separates the two filters on the screen.
        vulnerability(company, legacyServer,
                "Sistema operacional fora de suporte",
                "Risco aceito até a desativação do faturamento legado, já contratada.",
                Severity.CRITICAL, "9.6", null,
                daysAgo(now, 29), daysAgo(now, 10), null, admin,
                VulnerabilityStatus.ACCEPTED_RISK, null, counts);
        vulnerability(company, fileShare,
                "SMBv1 habilitado no compartilhamento",
                "Mantido por um integrador que ainda não suporta versões mais novas.",
                Severity.HIGH, "8.0", null,
                daysAgo(now, 23), null, null, admin,
                VulnerabilityStatus.ACCEPTED_RISK, null, counts);
        vulnerability(company, portalApi,
                "Stack trace exibido em desenvolvimento",
                "Aceito por ser um ambiente isolado, sem dados reais.",
                Severity.LOW, "2.4", null,
                daysAgo(now, 13), null, null, admin,
                VulnerabilityStatus.ACCEPTED_RISK, null, counts);
        vulnerability(company, supportWorkstation,
                "Bloqueio de tela após quinze minutos",
                "Política acima do padrão recomendado, aceita pela operação do suporte.",
                Severity.LOW, "2.0", null,
                daysAgo(now, 3), null, null, admin,
                VulnerabilityStatus.ACCEPTED_RISK, null, counts);

        // Six comments over three findings, written by four different roles, so the
        // discussion thread is not empty on the first vulnerability the visitor opens.
        comment(company, sqlInjection, analyst,
                "Reproduzido em homologação com um payload de união simples. Encaminhado ao time.", counts);
        comment(company, sqlInjection, developer,
                "Consulta migrada para parâmetros vinculados e coberta por teste de regressão.", counts);
        comment(company, sqlInjection, admin,
                "Correção validada em produção. Encerrando o item.", counts);
        comment(company, rateLimiting, developer,
                "Limite por cliente já implementado; falta publicar a configuração do gateway.", counts);
        comment(company, rateLimiting, analyst,
                "Prazo estourado. Combinamos nova janela para a próxima terça-feira.", counts);
        comment(company, backups, viewer,
                "Auditoria externa perguntou sobre este item na última reunião.", counts);
    }

    // ------------------------------------------------------------------ tenant 2

    /**
     * A second, deliberately small tenant. Logging in as this admin must show a completely
     * different dashboard, which makes the tenant isolation of the whole application
     * demonstrable in half a minute by anyone who clones the repository.
     */
    private void seedNorthwindTenant(Instant now, String passwordHash, Counts counts) {
        Company company = companyRepository.save(new Company("Northwind Labs", NORTHWIND_SLUG));
        counts.companies++;

        User admin = user(company, "Nina Ward", "admin@northwind.test", passwordHash, Role.ADMIN, counts);

        Project research = project(company, "Laboratório de Pesquisa",
                "Protótipos de telemetria industrial.", ProjectStatus.ACTIVE, admin, counts);

        Asset telemetryApi = asset(company, research, "API de Telemetria",
                "Coletor de métricas dos protótipos.",
                AssetType.API, "api-telemetria.northwind.test", Environment.STAGING, Criticality.MEDIUM, counts);
        Asset bench = asset(company, research, "Estação de Bancada",
                "Computador de bancada usado nos ensaios.",
                AssetType.WORKSTATION, "nw-bench-01", Environment.DEVELOPMENT, Criticality.LOW, counts);

        vulnerability(company, telemetryApi,
                "Token de API sem expiração",
                "Os tokens emitidos para os coletores não têm prazo de validade.",
                Severity.HIGH, "7.4", null,
                daysAgo(now, 12), daysAhead(now, 6), admin, admin,
                VulnerabilityStatus.OPEN, null, counts);
        vulnerability(company, telemetryApi,
                "Endpoint de ingestão sem autenticação",
                "Qualquer host da rede consegue publicar métricas falsas.",
                Severity.CRITICAL, "9.2", null,
                daysAgo(now, 10), daysAgo(now, 2), admin, admin,
                VulnerabilityStatus.IN_PROGRESS, null, counts);
        vulnerability(company, telemetryApi,
                "Runtime em versão fora de suporte",
                "A imagem base do coletor não recebe mais correções de segurança.",
                Severity.MEDIUM, "5.5", "CVE-2023-44487",
                daysAgo(now, 9), daysAhead(now, 9), null, admin,
                VulnerabilityStatus.OPEN, null, counts);
        vulnerability(company, bench,
                "Senha fraca no console de bancada",
                "A conta local usava uma senha de quatro caracteres.",
                Severity.MEDIUM, "6.0", null,
                daysAgo(now, 8), daysAgo(now, 3), admin, admin,
                VulnerabilityStatus.RESOLVED, daysAgo(now, 4), counts);
        vulnerability(company, bench,
                "Porta de depuração aberta",
                "Risco aceito enquanto os ensaios estiverem em andamento.",
                Severity.LOW, "3.3", null,
                daysAgo(now, 6), null, null, admin,
                VulnerabilityStatus.ACCEPTED_RISK, null, counts);
        vulnerability(company, bench,
                "Dependência transitiva desatualizada",
                "Biblioteca de serialização duas versões atrás da corrigida.",
                Severity.LOW, "2.9", null,
                daysAgo(now, 5), daysAhead(now, 15), null, admin,
                VulnerabilityStatus.OPEN, null, counts);
    }

    // ------------------------------------------------------------------ helpers

    private static Instant daysAgo(Instant now, int days) {
        return now.minus(days, ChronoUnit.DAYS);
    }

    private static Instant daysAhead(Instant now, int days) {
        return now.plus(days, ChronoUnit.DAYS);
    }

    private User user(Company company, String name, String email, String passwordHash, Role role, Counts counts) {
        User user = userRepository.save(new User(company, name, email, passwordHash, role));
        counts.users++;
        return user;
    }

    private Project project(Company company, String name, String description, ProjectStatus status,
                            User createdBy, Counts counts) {
        Project project = projectRepository.save(new Project(company, name, description, status, createdBy));
        counts.projects++;
        return project;
    }

    private Asset asset(Company company, Project project, String name, String description, AssetType type,
                        String identifier, Environment environment, Criticality criticality, Counts counts) {
        Asset asset = assetRepository.save(
                new Asset(company, project, name, description, type, identifier, environment, criticality));
        counts.assets++;
        return asset;
    }

    /**
     * The constructor of {@code Vulnerability} always starts a finding as {@code OPEN},
     * because only {@code PATCH /status} may move it; the seed therefore sets the final
     * status explicitly before the first save. The guard below repeats, in Java, the V5
     * CHECK that ties {@code resolvedAt} to {@code RESOLVED}, so a typo in the data above
     * fails with a readable message instead of a constraint violation.
     */
    private Vulnerability vulnerability(Company company, Asset asset, String title, String description,
                                        Severity severity, String cvssScore, String cve,
                                        Instant discoveredAt, Instant dueDate,
                                        User assignedTo, User createdBy,
                                        VulnerabilityStatus status, Instant resolvedAt, Counts counts) {
        boolean resolved = status == VulnerabilityStatus.RESOLVED;
        if (resolved != (resolvedAt != null)) {
            throw new IllegalStateException(
                    "Seed inválido para '" + title + "': resolvedAt deve existir se e somente se o status for RESOLVED");
        }
        Vulnerability vulnerability = new Vulnerability(company, asset, title, description, severity,
                cvssScore == null ? null : new BigDecimal(cvssScore), cve,
                discoveredAt, dueDate, assignedTo, createdBy);
        vulnerability.setStatus(status);
        vulnerability.setResolvedAt(resolvedAt);
        Vulnerability saved = vulnerabilityRepository.save(vulnerability);
        counts.vulnerabilities++;
        return saved;
    }

    private void comment(Company company, Vulnerability vulnerability, User author, String content, Counts counts) {
        commentRepository.save(new Comment(company, vulnerability, author, content));
        counts.comments++;
    }

    /** Mutable tally used only to build the final log line. */
    private static final class Counts {
        private int companies;
        private int users;
        private int projects;
        private int assets;
        private int vulnerabilities;
        private int comments;
    }
}
