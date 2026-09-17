package com.securityhub.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityhub.asset.Asset;
import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditLog;
import com.securityhub.audit.AuditLogRepository;
import com.securityhub.project.Project;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.vulnerability.Severity;
import com.securityhub.vulnerability.Vulnerability;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class AttachmentIntegrationTest extends AbstractIntegrationTest {

    private static final String VULNERABILITIES = "/api/v1/vulnerabilities";

    private static final long MAX_SIZE_BYTES = 4096L;

    private static final byte[] PDF =
            "%PDF-1.7\nprova de correção\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] ELF = {0x7F, 'E', 'L', 'F', 0x02, 0x01, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

    /**
     * Per class, and never {@code ./uploads}: a test that wrote into the directory the running
     * application uses would leave files behind in the working tree.
     */
    private static final Path UPLOAD_DIRECTORY = createUploadDirectory();

    @DynamicPropertySource
    static void attachmentProperties(DynamicPropertyRegistry registry) {
        registry.add("securityhub.attachments.directory", UPLOAD_DIRECTORY::toString);
        registry.add("securityhub.attachments.max-size-bytes", () -> MAX_SIZE_BYTES);
    }

    /** Only used by the orphan test; every other test runs against the real repository. */
    @SpyBean
    private AttachmentRepository attachmentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private TestDataFactory.Tenant acme;
    private TestDataFactory.Tenant globex;
    private long vulnerabilityId;

    @BeforeEach
    void createTenantAndVulnerability() throws IOException {
        clearUploadDirectory();
        acme = fixtures.tenant("acme");
        globex = fixtures.tenant("globex");
        Project portal = fixtures.project(acme.company, "Portal");
        Asset api = fixtures.asset(portal, "API de Cobrança");
        vulnerabilityId = fixtures.openVulnerability(api, "SQL Injection", Severity.HIGH, Instant.now())
                .getId();
    }

    // --- happy path ---------------------------------------------------------

    @Test
    void uploadsListsDownloadsAndDeletes() throws Exception {
        String created = mockMvc.perform(upload(acme.admin, vulnerabilityId,
                        file("prova.pdf", "application/pdf", PDF)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.filename").value("prova.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(PDF.length))
                .andExpect(jsonPath("$.checksumSha256").isString())
                .andExpect(jsonPath("$.uploadedBy.email").value(acme.admin.getEmail()))
                .andExpect(jsonPath("$.canDelete").value(true))
                .andReturn().getResponse().getContentAsString();
        long attachmentId = objectMapper.readTree(created).get("id").asLong();

        // Bare array, not the paginated envelope: the same deviation SeverityDistributionResponse
        // documents, for the same reason.
        mockMvc.perform(get(attachments(vulnerabilityId))
                        .header("Authorization", fixtures.bearer(acme.viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].filename").value("prova.pdf"))
                // A VIEWER may read the evidence and may never remove it.
                .andExpect(jsonPath("$[0].canDelete").value(false));

        byte[] downloaded = mockMvc.perform(get(attachments(vulnerabilityId) + "/" + attachmentId + "/download")
                        .header("Authorization", fixtures.bearer(acme.viewer)))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    assertThat(result.getResponse().getHeader("Content-Disposition"))
                            .startsWith("attachment;")
                            .contains("filename*=UTF-8''prova.pdf");
                    assertThat(result.getResponse().getHeader("Cache-Control"))
                            .isEqualTo("private, no-store");
                    assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
                    assertThat(result.getResponse().getContentLength()).isEqualTo(PDF.length);
                })
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(PDF);

        mockMvc.perform(delete(attachments(vulnerabilityId) + "/" + attachmentId)
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNoContent());

        assertThat(storedFiles()).isEmpty();
        mockMvc.perform(get(attachments(vulnerabilityId))
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void storesAnAccentedNameAndServesItBackOnTheDownload() throws Exception {
        long id = uploadAndGetId(acme.analyst, file("evidência crítica.png", "image/png", PNG));

        mockMvc.perform(get(attachments(vulnerabilityId) + "/" + id + "/download")
                        .header("Authorization", fixtures.bearer(acme.analyst)))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Content-Disposition"))
                        .contains("filename*=UTF-8''"));
    }

    // --- roles --------------------------------------------------------------

    @Test
    void adminAnalystAndDeveloperMayUploadAndViewerMayNot() throws Exception {
        for (User allowed : Arrays.asList(acme.admin, acme.analyst, acme.developer)) {
            mockMvc.perform(upload(allowed, vulnerabilityId, file("p.pdf", "application/pdf", PDF)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(upload(acme.viewer, vulnerabilityId, file("p.pdf", "application/pdf", PDF)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void theUploaderOrAnAdminMayDeleteAndAnotherDeveloperMayNot() throws Exception {
        User otherDeveloper = fixtures.user(acme.company, "dev2@acme.test", Role.DEVELOPER);
        long byDeveloper = uploadAndGetId(acme.developer, file("a.pdf", "application/pdf", PDF));
        long byAnalyst = uploadAndGetId(acme.analyst, file("b.pdf", "application/pdf", PDF));

        mockMvc.perform(delete(attachments(vulnerabilityId) + "/" + byDeveloper)
                        .header("Authorization", fixtures.bearer(otherDeveloper)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(delete(attachments(vulnerabilityId) + "/" + byDeveloper)
                        .header("Authorization", fixtures.bearer(acme.developer)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete(attachments(vulnerabilityId) + "/" + byAnalyst)
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNoContent());

        assertThat(storedFiles()).isEmpty();
    }

    // --- refusals -----------------------------------------------------------

    @Test
    void rejectsAnEmptyFile() throws Exception {
        mockMvc.perform(upload(acme.admin, vulnerabilityId, file("vazio.txt", "text/plain", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(storedFiles()).isEmpty();
    }

    @Test
    void rejectsAFileAboveTheConfiguredLimit() throws Exception {
        byte[] oversized = new byte[(int) MAX_SIZE_BYTES + 1];
        Arrays.fill(oversized, (byte) 'a');

        mockMvc.perform(upload(acme.admin, vulnerabilityId, file("grande.txt", "text/plain", oversized)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        assertThat(storedFiles()).isEmpty();
    }

    @Test
    void rejectsContentThatIsNotOnTheAllowlistEvenWhenThePartDeclaresAPdf() throws Exception {
        mockMvc.perform(upload(acme.admin, vulnerabilityId, file("falso.pdf", "application/pdf", ELF)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        assertThat(storedFiles()).isEmpty();
    }

    @Test
    void rejectsAJsonBodyOnTheMultipartEndpoint() throws Exception {
        mockMvc.perform(post(attachments(vulnerabilityId))
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"file\":\"nope\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void rejectsAMultipartRequestWithoutTheFilePart() throws Exception {
        mockMvc.perform(multipart(attachments(vulnerabilityId))
                        .file(new MockMultipartFile("outra", "x.pdf", "application/pdf", PDF))
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("file"));
    }

    @Test
    void rejectsTheTwentyFirstUpload() throws Exception {
        for (int i = 0; i < AttachmentService.MAX_ATTACHMENTS; i++) {
            mockMvc.perform(upload(acme.admin, vulnerabilityId, file("p" + i + ".pdf", "application/pdf", PDF)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(upload(acme.admin, vulnerabilityId, file("extra.pdf", "application/pdf", PDF)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(storedFiles()).hasSize(AttachmentService.MAX_ATTACHMENTS);
    }

    // --- path containment ---------------------------------------------------

    /**
     * The name is never used to build a path, so the file lands under its generated name and
     * the parent directory is untouched. The assertion on the parent is the one that would
     * fail the day someone "improves" the storage by keeping the original name.
     */
    @Test
    void aTraversalFilenameLandsInsideTheConfiguredDirectory() throws Exception {
        mockMvc.perform(upload(acme.admin, vulnerabilityId,
                        file("../../evil.txt", "text/plain", "conteudo".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("evil.txt"));

        List<Path> stored = storedFiles();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getFileName().toString()).matches("^[0-9a-f]{32}$");
        assertThat(Files.exists(UPLOAD_DIRECTORY.getParent().resolve("evil.txt"))).isFalse();
        assertThat(Files.exists(UPLOAD_DIRECTORY.getParent().getParent().resolve("evil.txt"))).isFalse();
    }

    // --- tenant isolation ---------------------------------------------------

    @Test
    void everyEndpointAnswersNotFoundForAVulnerabilityOfAnotherCompany() throws Exception {
        long attachmentId = uploadAndGetId(acme.admin, file("p.pdf", "application/pdf", PDF));
        String base = attachments(vulnerabilityId);
        String intruder = fixtures.bearer(globex.admin);

        mockMvc.perform(get(base).header("Authorization", intruder))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(upload(globex.admin, vulnerabilityId, file("p.pdf", "application/pdf", PDF)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(base + "/" + attachmentId + "/download").header("Authorization", intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(base + "/" + attachmentId).header("Authorization", intruder))
                .andExpect(status().isNotFound());

        assertThat(storedFiles()).hasSize(1);
    }

    @Test
    void anAttachmentReachedThroughTheWrongVulnerabilityIsNotFound() throws Exception {
        long attachmentId = uploadAndGetId(acme.admin, file("p.pdf", "application/pdf", PDF));
        Vulnerability other = fixtures.openVulnerability(
                fixtures.asset(fixtures.project(acme.company, "Outro"), "Outro ativo"),
                "Outra falha", Severity.LOW, Instant.now());

        mockMvc.perform(get(attachments(other.getId()) + "/" + attachmentId + "/download")
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNotFound());
    }

    // --- ordering that fails safe -------------------------------------------

    /**
     * The whole reason the file is written before the row. With the insert forced to fail, the
     * transaction rolls back and the {@code afterCompletion} hook has to take the file with it —
     * otherwise every failed upload would leave an orphan nothing references and nothing lists.
     */
    @Test
    void theFileIsRemovedWhenTheRowCannotBeInserted() throws Exception {
        willThrow(new IllegalStateException("falha simulada ao gravar a linha"))
                .given(attachmentRepository).save(any(Attachment.class));

        mockMvc.perform(upload(acme.admin, vulnerabilityId, file("p.pdf", "application/pdf", PDF)))
                .andExpect(status().isInternalServerError());

        assertThat(storedFiles()).isEmpty();
    }

    // --- deletion of the parent ---------------------------------------------

    @Test
    void deletingTheVulnerabilityRemovesTheRowsTheFilesAndRecordsHowMany() throws Exception {
        uploadAndGetId(acme.admin, file("a.pdf", "application/pdf", PDF));
        uploadAndGetId(acme.admin, file("b.png", "image/png", PNG));
        assertThat(storedFiles()).hasSize(2);

        mockMvc.perform(delete(VULNERABILITIES + "/" + vulnerabilityId)
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNoContent());

        assertThat(attachmentRepository.count()).isZero();
        assertThat(storedFiles()).isEmpty();

        AuditLog deletion = auditLogRepository.findAll().stream()
                .filter(entry -> entry.getAction() == AuditAction.DELETE
                        && "Vulnerability".equals(entry.getEntityType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nenhuma exclusão registrada"));
        JsonNode before = objectMapper.readTree(deletion.getOldValueJson());
        assertThat(before.get("attachmentCount").asLong()).isEqualTo(2L);
        assertThat(before.get("commentCount").asLong()).isZero();
    }

    // --- audit --------------------------------------------------------------

    @Test
    void theUploadWritesOneCreateEntryWithoutTheContentOfTheFile() throws Exception {
        byte[] secret = "MARCADOR-SECRETO dentro do arquivo\n".getBytes(StandardCharsets.UTF_8);

        uploadAndGetId(acme.developer, file("nota.txt", "text/plain", secret));

        List<AuditLog> entries = auditLogRepository.findAll().stream()
                .filter(entry -> "Attachment".equals(entry.getEntityType()))
                .collect(Collectors.toList());
        assertThat(entries).hasSize(1);

        AuditLog entry = entries.get(0);
        assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(entry.getActorEmail()).isEqualTo(acme.developer.getEmail());
        assertThat(entry.getNewValueJson()).doesNotContain("MARCADOR-SECRETO");

        JsonNode values = objectMapper.readTree(entry.getNewValueJson());
        assertThat(values.get("filename").asText()).isEqualTo("nota.txt");
        assertThat(values.get("contentType").asText()).isEqualTo("text/plain");
        assertThat(values.get("sizeBytes").asLong()).isEqualTo(secret.length);
        assertThat(values.get("checksumSha256").asText()).matches("^[0-9a-f]{64}$");
    }

    // --- helpers ------------------------------------------------------------

    private static String attachments(long id) {
        return VULNERABILITIES + "/" + id + "/attachments";
    }

    private MockMultipartFile file(String name, String declaredContentType, byte[] content) {
        return new MockMultipartFile("file", name, declaredContentType, content);
    }

    private MockHttpServletRequestBuilder upload(User actor, long id, MockMultipartFile file) {
        return MockMvcRequestBuilders.multipart(attachments(id))
                .file(file)
                .header("Authorization", fixtures.bearer(actor));
    }

    private long uploadAndGetId(User actor, MockMultipartFile file) throws Exception {
        String created = mockMvc.perform(upload(actor, vulnerabilityId, file))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asLong();
    }

    private List<Path> storedFiles() throws IOException {
        try (Stream<Path> files = Files.list(UPLOAD_DIRECTORY)) {
            return files.sorted(Comparator.comparing(Path::toString)).collect(Collectors.toList());
        }
    }

    private void clearUploadDirectory() throws IOException {
        if (!Files.isDirectory(UPLOAD_DIRECTORY)) {
            return;
        }
        for (Path file : storedFiles()) {
            Files.deleteIfExists(file);
        }
    }

    private static Path createUploadDirectory() {
        try {
            return Files.createTempDirectory("securityhub-anexos").toRealPath();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
