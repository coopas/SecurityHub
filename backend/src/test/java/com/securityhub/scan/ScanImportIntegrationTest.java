package com.securityhub.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securityhub.asset.Asset;
import com.securityhub.asset.AssetRepository;
import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditLog;
import com.securityhub.audit.AuditLogRepository;
import com.securityhub.project.Project;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.user.User;
import com.securityhub.vulnerability.Vulnerability;
import com.securityhub.vulnerability.VulnerabilityRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The whole import lifecycle against a real PostgreSQL: upload, preview, mapping, confirm and
 * discard, plus the refusals and the tenant boundary.
 *
 * <p>The limits are lowered to values a test can reach cheaply. {@link #MAX_FINDINGS} is six on
 * purpose: the ZAP fixture produces exactly six findings, so the happy path also proves the
 * boundary is inclusive, and a seven-line nuclei report is enough to cross it.
 */
class ScanImportIntegrationTest extends AbstractIntegrationTest {

    private static final String SCAN_IMPORTS = "/api/v1/scan-imports";

    private static final long MAX_UPLOAD_BYTES = 4096L;
    private static final int MAX_FINDINGS = 6;

    /**
     * Per class, and never the configured directory of a running application: a test that wrote
     * there would leave files behind in the working tree.
     */
    private static final Path SCAN_DIRECTORY = createScanDirectory();

    @DynamicPropertySource
    static void scanProperties(DynamicPropertyRegistry registry) {
        registry.add("securityhub.scan.directory", SCAN_DIRECTORY::toString);
        registry.add("securityhub.scan.max-upload-bytes", () -> MAX_UPLOAD_BYTES);
        registry.add("securityhub.scan.max-findings", () -> MAX_FINDINGS);
    }

    @Autowired
    private ScanImportRepository scanImportRepository;

    @Autowired
    private ScanFindingRepository scanFindingRepository;

    @Autowired
    private VulnerabilityRepository vulnerabilityRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private TestDataFactory.Tenant acme;
    private TestDataFactory.Tenant globex;
    private Project portal;

    @BeforeEach
    void createTenants() throws IOException {
        clearScanDirectory();
        acme = fixtures.tenant("acme");
        globex = fixtures.tenant("globex");
        portal = fixtures.project(acme.company, "Portal");
    }

    // --- the three formats --------------------------------------------------

    @Test
    void stagesAnNmapReportMatchingOnlyTheTargetsThatAreRegisteredAssets() throws Exception {
        fixtures.asset(portal, "Web", "web.acme.test");

        mockMvc.perform(upload(acme.admin, portal.getId(), ScanFormat.NMAP_XML, fixture("nmap-vuln.xml")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.projectId").value(portal.getId()))
                .andExpect(jsonPath("$.projectName").value("Portal"))
                .andExpect(jsonPath("$.format").value("NMAP_XML"))
                .andExpect(jsonPath("$.originalFilename").value("nmap-vuln.xml"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.importedByName").value(acme.admin.getName()))
                .andExpect(jsonPath("$.totalFindings").value(3))
                .andExpect(jsonPath("$.matchedCount").value(2))
                .andExpect(jsonPath("$.unmatchedCount").value(1))
                .andExpect(jsonPath("$.duplicateCount").value(0))
                // Nothing is imported or skipped until somebody confirms.
                .andExpect(jsonPath("$.importedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.findings.length()").value(3))
                .andExpect(jsonPath("$.findings[0].ruleId").value("ssl-heartbleed"))
                .andExpect(jsonPath("$.findings[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.findings[0].cve").value("CVE-2014-0160"))
                .andExpect(jsonPath("$.findings[0].target").value("web.acme.test"))
                .andExpect(jsonPath("$.findings[0].status").value("MATCHED"))
                .andExpect(jsonPath("$.findings[0].assetName").value("Web"))
                // Absent, not null: default-property-inclusion drops them.
                .andExpect(jsonPath("$.findings[0].vulnerabilityId").doesNotExist())
                .andExpect(jsonPath("$.findings[2].ruleId").value("smb-vuln-ms17-010"))
                .andExpect(jsonPath("$.findings[2].target").value("10.0.0.11"))
                .andExpect(jsonPath("$.findings[2].status").value("UNMATCHED"))
                .andExpect(jsonPath("$.findings[2].assetId").doesNotExist());

        // The upload writes the report and nothing else: no vulnerability exists yet.
        assertThat(vulnerabilityRepository.count()).isZero();
        assertThat(storedFiles()).hasSize(1);
        assertThat(storedFiles().get(0).getFileName().toString()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void stagesOneFindingPerZapInstanceUpToTheInclusiveLimit() throws Exception {
        fixtures.asset(portal, "Site", "https://web.acme.test");

        mockMvc.perform(upload(acme.analyst, portal.getId(), ScanFormat.ZAP_JSON,
                        fixture("zap-report.json")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.format").value("ZAP_JSON"))
                // Exactly securityhub.scan.max-findings: the ceiling is inclusive.
                .andExpect(jsonPath("$.totalFindings").value(MAX_FINDINGS))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.unmatchedCount").value(5))
                .andExpect(jsonPath("$.findings[0].ruleId").value("40012"))
                .andExpect(jsonPath("$.findings[0].target").value("https://web.acme.test/search?q=1"))
                // Three XSS instances come first, so Log4Shell is the fourth row; its 7.53
                // was rounded to the one decimal the NUMERIC(3,1) column accepts.
                .andExpect(jsonPath("$.findings[3].ruleId").value("10038"))
                .andExpect(jsonPath("$.findings[3].cve").value("CVE-2021-44228"))
                .andExpect(jsonPath("$.findings[3].cvssScore").value(7.5))
                // The alert with no instances falls back to the site name, which is the asset.
                .andExpect(jsonPath("$.findings[5].target").value("https://web.acme.test"))
                .andExpect(jsonPath("$.findings[5].status").value("MATCHED"));
    }

    @Test
    void stagesANucleiStreamSkippingTheLineThatIsNotJson() throws Exception {
        fixtures.asset(portal, "API", "http://api.acme.test");

        mockMvc.perform(upload(acme.admin, portal.getId(), ScanFormat.NUCLEI_JSONL,
                        fixture("nuclei-findings.jsonl")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.format").value("NUCLEI_JSONL"))
                .andExpect(jsonPath("$.totalFindings").value(3))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.unmatchedCount").value(2))
                .andExpect(jsonPath("$.findings[1].ruleId").value("tech-detect"))
                .andExpect(jsonPath("$.findings[1].target").value("http://api.acme.test"))
                .andExpect(jsonPath("$.findings[1].status").value("MATCHED"));
    }

    // --- matching -----------------------------------------------------------

    /**
     * The identifier index of V4 is {@code lower(identifier)} and does not trim, so the
     * importer has to normalize before it looks up. Without the trim, {@code "WEB.ACME.TEST "}
     * would be reported as unmatched against an asset sitting right there.
     */
    @Test
    void matchesATargetIgnoringCaseAndSurroundingWhitespace() throws Exception {
        fixtures.asset(portal, "Web", "web.acme.test");

        mockMvc.perform(upload(acme.admin, portal.getId(), ScanFormat.NMAP_XML,
                        file("scan.xml", NMAP_WITH_PADDED_UPPERCASE_TARGET)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalFindings").value(1))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.findings[0].target").value("WEB.ACME.TEST "))
                .andExpect(jsonPath("$.findings[0].assetName").value("Web"));
    }

    /** An unresolved target waits for a person; it never invents the asset it did not find. */
    @Test
    void neverCreatesAnAssetForAnUnmatchedTarget() throws Exception {
        long before = assetRepository.count();

        mockMvc.perform(upload(acme.admin, portal.getId(), ScanFormat.NMAP_XML, fixture("nmap-vuln.xml")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matchedCount").value(0))
                .andExpect(jsonPath("$.unmatchedCount").value(3));

        assertThat(assetRepository.count()).isEqualTo(before);
    }

    // --- mapping ------------------------------------------------------------

    @Test
    void mapsAnUnmatchedFindingToAnAssetAndRecountsTheImport() throws Exception {
        fixtures.asset(portal, "Web", "web.acme.test");
        Asset fileServer = fixtures.asset(portal, "File Server", "10.0.0.11-nao-bate");
        long importId = uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));
        long findingId = findingIdAt(importId, 2);

        mockMvc.perform(map(acme.analyst, importId, findingId, fileServer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(findingId))
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.assetId").value(fileServer.getId()))
                .andExpect(jsonPath("$.assetName").value("File Server"));

        mockMvc.perform(get(SCAN_IMPORTS + "/" + importId)
                        .header("Authorization", fixtures.bearer(acme.viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedCount").value(3))
                .andExpect(jsonPath("$.unmatchedCount").value(0));
    }

    @Test
    void refusesToMapAFindingThatAlreadyHasAnAsset() throws Exception {
        Asset web = fixtures.asset(portal, "Web", "web.acme.test");
        long importId = uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));

        mockMvc.perform(map(acme.admin, importId, findingIdAt(importId, 0), web.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void refusesToMapToAnAssetOfAnotherCompany() throws Exception {
        Asset intruder = fixtures.asset(fixtures.project(globex.company, "Outro"), "Deles", "deles");
        long importId = uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));

        mockMvc.perform(map(acme.admin, importId, findingIdAt(importId, 0), intruder.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- confirm ------------------------------------------------------------

    @Test
    void confirmCreatesOneVulnerabilityPerMatchedFindingAndSkipsTheRest() throws Exception {
        fixtures.asset(portal, "Web", "web.acme.test");
        long importId = uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));

        mockMvc.perform(confirm(acme.analyst, importId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.importedCount").value(2))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.matchedCount").value(0))
                .andExpect(jsonPath("$.unmatchedCount").value(0))
                .andExpect(jsonPath("$.findings[0].status").value("IMPORTED"))
                .andExpect(jsonPath("$.findings[0].vulnerabilityId").isNumber())
                .andExpect(jsonPath("$.findings[2].status").value("SKIPPED"))
                .andExpect(jsonPath("$.findings[2].vulnerabilityId").doesNotExist());

        List<Vulnerability> created = vulnerabilityRepository.findAll();
        assertThat(created).hasSize(2);
        assertThat(created).extracting(Vulnerability::getTitle)
                .containsExactlyInAnyOrder("ssl-heartbleed", "http-server-header");
        // The fingerprint is what makes the next import of this report a no-op.
        assertThat(created).allSatisfy(vulnerability ->
                assertThat(vulnerability.getFingerprint()).matches("^[0-9a-f]{64}$"));
        assertThat(created).extracting(Vulnerability::getFingerprint).doesNotHaveDuplicates();
    }

    /**
     * Regression: the staged finding kept a foreign key to the vulnerability it created, so
     * importing one made it permanently undeletable — DELETE answered 409, and by the rule that
     * a parent with children cannot go, its asset and its project were stuck with it. V10 lets
     * the database drop the link on its own.
     *
     * <p>The finding survives with its status intact. `scan_findings` records what the report
     * found and what was decided about it; the import really did import this one, and saying
     * otherwise afterwards would make the history lie about a decision nobody revisited.
     */
    @Test
    void aVulnerabilityCreatedByAnImportCanStillBeDeletedAndLeavesItsFindingBehind() throws Exception {
        fixtures.asset(portal, "Web", "web.acme.test");
        long importId = uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));
        mockMvc.perform(confirm(acme.admin, importId)).andExpect(status().isOk());

        Vulnerability imported = vulnerabilityRepository.findAll().get(0);
        Long importedId = imported.getId();

        mockMvc.perform(delete("/api/v1/vulnerabilities/" + importedId)
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNoContent());

        assertThat(vulnerabilityRepository.findById(importedId)).isEmpty();
        // Asked through the preview because `open-in-view` is disabled: reading the association
        // off a detached entity here would say more about the test than about the schema.
        mockMvc.perform(get(SCAN_IMPORTS + "/" + importId)
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findings[0].status").value("IMPORTED"))
                .andExpect(jsonPath("$.findings[0].vulnerabilityId").doesNotExist());
        assertThat(scanFindingRepository.count()).isEqualTo(3L);
    }

    @Test
    void confirmingTwiceIsAConflictAndCreatesNothingTheSecondTime() throws Exception {
        fixtures.asset(portal, "Web", "web.acme.test");
        long importId = uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));

        mockMvc.perform(confirm(acme.admin, importId)).andExpect(status().isOk());
        mockMvc.perform(confirm(acme.admin, importId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(vulnerabilityRepository.count()).isEqualTo(2L);
    }

    // --- deduplication ------------------------------------------------------

    /**
     * The rule the whole feature turns on: a re-import must not undo human work. Every finding
     * of the second upload is already in the backlog, so nothing is created and nothing is
     * touched — not a status somebody moved, not an assignee somebody chose.
     */
    @Test
    void aSecondImportOfTheSameReportCreatesNothingAndReportsEveryFindingAsDuplicate()
            throws Exception {
        fixtures.asset(portal, "Web", "web.acme.test");
        fixtures.asset(portal, "File Server", "10.0.0.11");
        long first = uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));
        mockMvc.perform(confirm(acme.admin, first))
                .andExpect(jsonPath("$.importedCount").value(3));
        assertThat(vulnerabilityRepository.count()).isEqualTo(3L);

        mockMvc.perform(upload(acme.admin, portal.getId(), ScanFormat.NMAP_XML, fixture("nmap-vuln.xml")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalFindings").value(3))
                .andExpect(jsonPath("$.duplicateCount").value(3))
                .andExpect(jsonPath("$.matchedCount").value(0))
                .andExpect(jsonPath("$.unmatchedCount").value(0))
                .andExpect(jsonPath("$.findings[0].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.findings[1].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.findings[2].status").value("DUPLICATE"));

        long second = scanImportRepository.findAll().stream()
                .mapToLong(ScanImport::getId).max().orElseThrow(AssertionError::new);
        mockMvc.perform(confirm(acme.admin, second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(3));

        assertThat(vulnerabilityRepository.count()).isEqualTo(3L);
    }

    /**
     * Both reports are staged before either is confirmed, which is the case the upload-time
     * check cannot see: while the first import is still PENDING the backlog is empty, so the
     * second upload honestly finds no duplicate and stages every finding as MATCHED. By the
     * time it is confirmed that answer is stale. Two analysts importing the same scan, or one
     * person uploading twice before reviewing, is an ordinary Tuesday — and without a
     * re-check at confirm it ends on uk_vulnerabilities_company_fingerprint, surfacing as a
     * 409 about nothing the user did.
     */
    @Test
    void confirmingTwoImportsOfTheSameReportStagedBeforeEitherWasConfirmedSkipsTheRepeats()
            throws Exception {
        fixtures.asset(portal, "Web", "web.acme.test");
        fixtures.asset(portal, "File Server", "10.0.0.11");

        long first = uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));
        long second = uploadAndGetId(acme.analyst, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));

        mockMvc.perform(confirm(acme.admin, first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(3));

        mockMvc.perform(confirm(acme.analyst, second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(3));

        assertThat(vulnerabilityRepository.count()).isEqualTo(3L);
    }

    /**
     * The service skips duplicates, and it is right to — but the guarantee is the index, which
     * holds for a second importer, a concurrent confirm or anything written by hand.
     */
    @Test
    void theUniqueIndexRejectsASecondVulnerabilityWithTheSameCompanyAndFingerprint() {
        Asset web = fixtures.asset(portal, "Web", "web.acme.test");
        String fingerprint = "ab12cd34".repeat(8);
        fixtures.fingerprintedVulnerability(web, "Primeira", fingerprint);

        assertThatThrownBy(() -> fixtures.fingerprintedVulnerability(web, "Segunda", fingerprint))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** The index is partial, so the hand-written findings that share a null do not collide. */
    @Test
    void twoVulnerabilitiesWithoutAFingerprintCoexist() {
        Asset web = fixtures.asset(portal, "Web", "web.acme.test");

        fixtures.openVulnerability(web, "Manual A", com.securityhub.vulnerability.Severity.LOW,
                java.time.Instant.now());
        fixtures.openVulnerability(web, "Manual B", com.securityhub.vulnerability.Severity.LOW,
                java.time.Instant.now());

        assertThat(vulnerabilityRepository.count()).isEqualTo(2L);
    }

    // --- discard ------------------------------------------------------------

    @Test
    void discardRemovesTheFileAndKeepsWhatTheReportFound() throws Exception {
        long importId = uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));
        assertThat(storedFiles()).hasSize(1);

        mockMvc.perform(delete(SCAN_IMPORTS + "/" + importId)
                        .header("Authorization", fixtures.bearer(acme.analyst)))
                .andExpect(status().isNoContent());

        assertThat(storedFiles()).isEmpty();
        // The row and its findings stay: what a scan found is a fact worth keeping even when
        // nobody acted on it.
        mockMvc.perform(get(SCAN_IMPORTS + "/" + importId)
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCARDED"))
                .andExpect(jsonPath("$.totalFindings").value(3));
        assertThat(vulnerabilityRepository.count()).isZero();
    }

    @Test
    void refusesToDiscardAnImportThatWasAlreadyConfirmed() throws Exception {
        fixtures.asset(portal, "Web", "web.acme.test");
        long importId = uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));
        mockMvc.perform(confirm(acme.admin, importId)).andExpect(status().isOk());

        mockMvc.perform(delete(SCAN_IMPORTS + "/" + importId)
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isConflict());

        // The report behind existing vulnerabilities is the one file that has earned its place.
        assertThat(storedFiles()).hasSize(1);
    }

    // --- refusals -----------------------------------------------------------

    /**
     * The check that has to happen before anything is written. A refused report must leave the
     * database and the directory exactly as it found them.
     */
    @Test
    void refusesAReportAboveTheFindingsLimitWithoutWritingARowOrAFile() throws Exception {
        mockMvc.perform(upload(acme.admin, portal.getId(), ScanFormat.NUCLEI_JSONL,
                        file("grande.jsonl", nucleiLines(MAX_FINDINGS + 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "limite por importação é " + MAX_FINDINGS)));

        assertThat(scanImportRepository.count()).isZero();
        assertThat(scanFindingRepository.count()).isZero();
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    void refusesAReportAboveTheSizeLimit() throws Exception {
        byte[] oversized = new byte[(int) MAX_UPLOAD_BYTES + 1];
        Arrays.fill(oversized, (byte) 'a');

        mockMvc.perform(upload(acme.admin, portal.getId(), ScanFormat.NUCLEI_JSONL,
                        file("grande.jsonl", oversized)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        assertThat(scanImportRepository.count()).isZero();
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    void refusesAnEmptyReport() throws Exception {
        mockMvc.perform(upload(acme.admin, portal.getId(), ScanFormat.NMAP_XML,
                        file("vazio.xml", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(storedFiles()).isEmpty();
    }

    @Test
    void refusesAReportThatCannotBeParsed() throws Exception {
        mockMvc.perform(upload(acme.admin, portal.getId(), ScanFormat.NMAP_XML,
                        file("quebrado.xml", "<nmaprun><host>".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(storedFiles()).isEmpty();
    }

    // --- tenant isolation ---------------------------------------------------

    @Test
    void refusesAProjectOfAnotherCompanyAsNotFound() throws Exception {
        Project theirs = fixtures.project(globex.company, "Projeto Deles");

        mockMvc.perform(upload(acme.admin, theirs.getId(), ScanFormat.NMAP_XML, fixture("nmap-vuln.xml")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(storedFiles()).isEmpty();
    }

    @Test
    void everyEndpointAnswersNotFoundForAnImportOfAnotherCompany() throws Exception {
        Asset web = fixtures.asset(portal, "Web", "web.acme.test");
        long importId = uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));
        long findingId = findingIdAt(importId, 2);
        String intruder = fixtures.bearer(globex.admin);

        mockMvc.perform(get(SCAN_IMPORTS + "/" + importId).header("Authorization", intruder))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(map(globex.admin, importId, findingId, web.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(confirm(globex.admin, importId))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(SCAN_IMPORTS + "/" + importId).header("Authorization", intruder))
                .andExpect(status().isNotFound());

        // Nothing was created and nothing was removed on the other side of the boundary.
        assertThat(vulnerabilityRepository.count()).isZero();
        assertThat(storedFiles()).hasSize(1);
    }

    @Test
    void theHistoryOnlyListsTheImportsOfTheCallersCompany() throws Exception {
        uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));
        uploadAndGetId(acme.analyst, ScanFormat.NUCLEI_JSONL, fixture("nuclei-findings.jsonl"));
        Project theirs = fixtures.project(globex.company, "Projeto Deles");
        mockMvc.perform(upload(globex.admin, theirs.getId(), ScanFormat.NMAP_XML,
                fixture("nmap-vuln.xml"))).andExpect(status().isCreated());

        mockMvc.perform(get(SCAN_IMPORTS).header("Authorization", fixtures.bearer(acme.viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                // Newest first, and the listing carries no findings.
                .andExpect(jsonPath("$.content[0].format").value("NUCLEI_JSONL"))
                .andExpect(jsonPath("$.content[0].totalFindings").value(3))
                .andExpect(jsonPath("$.content[0].findings").doesNotExist())
                .andExpect(jsonPath("$.content[1].format").value("NMAP_XML"));
    }

    // --- roles --------------------------------------------------------------

    @Test
    void adminAndAnalystMayImportAndDeveloperAndViewerMayNot() throws Exception {
        for (User allowed : Arrays.asList(acme.admin, acme.analyst)) {
            mockMvc.perform(upload(allowed, portal.getId(), ScanFormat.NMAP_XML, fixture("nmap-vuln.xml")))
                    .andExpect(status().isCreated());
        }

        for (User denied : Arrays.asList(acme.developer, acme.viewer)) {
            mockMvc.perform(upload(denied, portal.getId(), ScanFormat.NMAP_XML, fixture("nmap-vuln.xml")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        assertThat(scanImportRepository.count()).isEqualTo(2L);
        assertThat(storedFiles()).hasSize(2);
    }

    @Test
    void confirmMapAndDiscardAreClosedToDeveloperAndViewer() throws Exception {
        Asset web = fixtures.asset(portal, "Web", "web.acme.test");
        long importId = uploadAndGetId(acme.admin, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));
        long findingId = findingIdAt(importId, 2);

        for (User denied : Arrays.asList(acme.developer, acme.viewer)) {
            mockMvc.perform(map(denied, importId, findingId, web.getId()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(confirm(denied, importId))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete(SCAN_IMPORTS + "/" + importId)
                            .header("Authorization", fixtures.bearer(denied)))
                    .andExpect(status().isForbidden());
        }

        // A read is open to every member of the company, like the vulnerabilities themselves.
        mockMvc.perform(get(SCAN_IMPORTS + "/" + importId)
                        .header("Authorization", fixtures.bearer(acme.viewer)))
                .andExpect(status().isOk());
    }

    // --- audit --------------------------------------------------------------

    /**
     * One row for the whole import, and it is the reason vulnerabilities are not created
     * through {@code VulnerabilityService.create}: a CREATE per finding would bury the trail of
     * a company under hundreds of rows saying the same thing.
     */
    @Test
    void theConfirmWritesExactlyOneScanImportEntryCarryingOnlyTheSummary() throws Exception {
        fixtures.asset(portal, "Web", "web.acme.test");
        long importId = uploadAndGetId(acme.analyst, ScanFormat.NMAP_XML, fixture("nmap-vuln.xml"));
        mockMvc.perform(confirm(acme.analyst, importId)).andExpect(status().isOk());

        List<AuditLog> entries = auditLogRepository.findAll().stream()
                .filter(entry -> "ScanImport".equals(entry.getEntityType()))
                .collect(Collectors.toList());
        assertThat(entries).hasSize(1);

        AuditLog entry = entries.get(0);
        assertThat(entry.getAction()).isEqualTo(AuditAction.SCAN_IMPORT);
        assertThat(entry.getActorEmail()).isEqualTo(acme.analyst.getEmail());
        assertThat(entry.getEntityId()).isEqualTo(importId);

        com.fasterxml.jackson.databind.JsonNode values =
                objectMapper.readTree(entry.getNewValueJson());
        assertThat(values.get("totalFindings").asInt()).isEqualTo(3);
        assertThat(values.get("importedCount").asInt()).isEqualTo(2);
        assertThat(values.get("skippedCount").asInt()).isEqualTo(1);
        assertThat(values.get("filename").asText()).isEqualTo("nmap-vuln.xml");
        // Counters and the filename, never per-finding detail: AuditService.toJson truncates at
        // 8000 characters and a summary that grew with the report would be stored cut in half.
        assertThat(entry.getNewValueJson())
                .doesNotContain("ssl-heartbleed")
                .doesNotContain("CVE-2014-0160")
                .doesNotContain("10.0.0.11");
        assertThat(entry.getNewValueJson().length()).isLessThan(8000);

        // And no CREATE row per created vulnerability, which is the other half of the same rule.
        assertThat(auditLogRepository.findAll()).noneMatch(log -> log.getAction() == AuditAction.CREATE
                && "Vulnerability".equals(log.getEntityType()));
    }

    // --- helpers ------------------------------------------------------------

    /**
     * One host, no {@code <hostnames>}, and an {@code addr} the parser hands over untouched —
     * which is what lets this exercise the importer's own normalization rather than the
     * parser's.
     */
    private static final byte[] NMAP_WITH_PADDED_UPPERCASE_TARGET = ("<?xml version=\"1.0\"?>"
            + "<nmaprun scanner=\"nmap\" start=\"1700000000\" version=\"7.94\">"
            + "<host><status state=\"up\"/><address addr=\"WEB.ACME.TEST \" addrtype=\"ipv4\"/>"
            + "<hostscript><script id=\"ssl-heartbleed\" output=\"VULNERABLE\"/></hostscript>"
            + "</host></nmaprun>").getBytes(StandardCharsets.UTF_8);

    private static byte[] nucleiLines(int count) {
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < count; i++) {
            report.append("{\"template-id\":\"regra-").append(i)
                    .append("\",\"info\":{\"name\":\"Achado ").append(i)
                    .append("\",\"severity\":\"low\"},\"matched-at\":\"http://host-").append(i)
                    .append(".acme.test\",\"timestamp\":\"2023-11-13T10:15:00Z\"}\n");
        }
        return report.toString().getBytes(StandardCharsets.UTF_8);
    }

    private MockMultipartFile fixture(String name) {
        try (InputStream input = getClass().getResourceAsStream("/scan-fixtures/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Fixture de scan ausente: " + name);
            }
            return file(name, readAll(input));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, MediaType.TEXT_PLAIN_VALUE, content);
    }

    private MockHttpServletRequestBuilder upload(User actor, Long projectId, ScanFormat format,
                                                 MockMultipartFile file) {
        return MockMvcRequestBuilders.multipart(SCAN_IMPORTS)
                .file(file)
                .param("projectId", String.valueOf(projectId))
                .param("format", format.name())
                .header("Authorization", fixtures.bearer(actor));
    }

    private MockHttpServletRequestBuilder map(User actor, long importId, long findingId, long assetId) {
        return patch(SCAN_IMPORTS + "/" + importId + "/findings/" + findingId)
                .header("Authorization", fixtures.bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":" + assetId + "}");
    }

    private MockHttpServletRequestBuilder confirm(User actor, long importId) {
        return post(SCAN_IMPORTS + "/" + importId + "/confirm")
                .header("Authorization", fixtures.bearer(actor));
    }

    private long uploadAndGetId(User actor, ScanFormat format, MockMultipartFile file) throws Exception {
        String created = mockMvc.perform(upload(actor, portal.getId(), format, file))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asLong();
    }

    private long findingIdAt(long importId, int index) throws Exception {
        String body = mockMvc.perform(get(SCAN_IMPORTS + "/" + importId)
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("findings").get(index).get("id").asLong();
    }

    private List<Path> storedFiles() throws IOException {
        try (Stream<Path> files = Files.list(SCAN_DIRECTORY)) {
            return files.sorted(Comparator.comparing(Path::toString)).collect(Collectors.toList());
        }
    }

    private void clearScanDirectory() throws IOException {
        if (!Files.isDirectory(SCAN_DIRECTORY)) {
            return;
        }
        for (Path file : storedFiles()) {
            Files.deleteIfExists(file);
        }
    }

    private static Path createScanDirectory() {
        try {
            return Files.createTempDirectory("securityhub-scans").toRealPath();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
