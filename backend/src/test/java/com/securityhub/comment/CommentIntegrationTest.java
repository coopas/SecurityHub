package com.securityhub.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securityhub.asset.AssetType;
import com.securityhub.asset.Criticality;
import com.securityhub.asset.Environment;
import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditLog;
import com.securityhub.audit.AuditLogRepository;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.user.User;
import com.securityhub.vulnerability.Severity;
import com.securityhub.vulnerability.VulnerabilityRepository;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** and comments, the author-or-ADMIN edit rule and the VIEWER lockout. */
class CommentIntegrationTest extends AbstractIntegrationTest {

    private static final String VULNERABILITIES = "/api/v1/vulnerabilities";
    private static final String PROJECTS = "/api/v1/projects";
    private static final String ASSETS = "/api/v1/assets";

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private VulnerabilityRepository vulnerabilityRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private TestDataFactory.Tenant acme;
    private long api;
    private long vulnerability;

    @BeforeEach
    void createTenantAndVulnerability() throws Exception {
        acme = fixtures.tenant("acme");
        long portal = createProject("Portal");
        api = createAsset(portal, "API de Cobrança");
        vulnerability = createVulnerability("SQL Injection no login");
    }

    private String commentsOf(long vulnerabilityId) {
        return VULNERABILITIES + "/" + vulnerabilityId + "/comments";
    }

    // --- role matrix --------------------------------------------------------

    @Test
    void adminAnalystAndDeveloperCommentAndViewerCannot() throws Exception {
        for (User author : new User[]{acme.admin, acme.analyst, acme.developer}) {
            mockMvc.perform(post(commentsOf(vulnerability))
                            .header("Authorization", fixtures.bearer(author))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(content("  Comentário de " + author.getRole() + "  "))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.vulnerabilityId").value(vulnerability))
                    .andExpect(jsonPath("$.content").value("Comentário de " + author.getRole()))
                    .andExpect(jsonPath("$.author.email").value(author.getEmail()))
                    .andExpect(jsonPath("$.editable").value(true))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }

        mockMvc.perform(post(commentsOf(vulnerability))
                        .header("Authorization", fixtures.bearer(acme.viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(content("Comentário proibido"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // VIEWER is read-only, not blind.
        mockMvc.perform(get(commentsOf(vulnerability)).header("Authorization", fixtures.bearer(acme.viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].editable").value(false));

        assertThat(commentRepository.count()).isEqualTo(3);
    }

    @Test
    void theListingIsPagedAndReadsOldestFirst() throws Exception {
        comment(acme.admin, "Primeiro");
        comment(acme.analyst, "Segundo");
        comment(acme.developer, "Terceiro");

        mockMvc.perform(get(commentsOf(vulnerability)).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("Primeiro"))
                .andExpect(jsonPath("$.content[2].content").value("Terceiro"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.sort").value("createdAt,asc;id,asc"));

        mockMvc.perform(get(commentsOf(vulnerability)).header("Authorization", fixtures.bearer(acme.admin))
                        .param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    // --- edit rule (docs/data-model.md) ---------------------------------------------

    @Test
    void onlyTheAuthorOrAnAdminMayEditAComment() throws Exception {
        long fromDeveloper = comment(acme.developer, "Vou investigar");

        mockMvc.perform(put(commentsOf(vulnerability) + "/" + fromDeveloper)
                        .header("Authorization", fixtures.bearer(acme.developer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(content("Vou investigar hoje"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Vou investigar hoje"));

        mockMvc.perform(put(commentsOf(vulnerability) + "/" + fromDeveloper)
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(content("Moderado pelo admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Moderado pelo admin"));

        // ANALYST may comment, but not rewrite someone else's comment.
        mockMvc.perform(put(commentsOf(vulnerability) + "/" + fromDeveloper)
                        .header("Authorization", fixtures.bearer(acme.analyst))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(content("Tentativa do analyst"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // VIEWER does not even reach the ownership check.
        mockMvc.perform(put(commentsOf(vulnerability) + "/" + fromDeveloper)
                        .header("Authorization", fixtures.bearer(acme.viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(content("Tentativa do viewer"))))
                .andExpect(status().isForbidden());

        assertThat(commentRepository.findById(fromDeveloper).orElseThrow(AssertionError::new).getContent())
                .isEqualTo("Moderado pelo admin");
    }

    @Test
    void editableMirrorsTheServerSideRuleForEachReader() throws Exception {
        comment(acme.developer, "Do developer");

        mockMvc.perform(get(commentsOf(vulnerability)).header("Authorization", fixtures.bearer(acme.developer)))
                .andExpect(jsonPath("$.content[0].editable").value(true));
        mockMvc.perform(get(commentsOf(vulnerability)).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(jsonPath("$.content[0].editable").value(true));
        mockMvc.perform(get(commentsOf(vulnerability)).header("Authorization", fixtures.bearer(acme.analyst)))
                .andExpect(jsonPath("$.content[0].editable").value(false));
    }

    @Test
    void thereIsNoCommentDeletionInTheMvp() throws Exception {
        long id = comment(acme.admin, "Permanente");

        mockMvc.perform(delete(commentsOf(vulnerability) + "/" + id)
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isMethodNotAllowed());

        assertThat(commentRepository.count()).isEqualTo(1);
    }

    // --- validation and isolation -------------------------------------------

    @Test
    void aBlankCommentIsRejectedWithTheValidationEnvelope() throws Exception {
        mockMvc.perform(post(commentsOf(vulnerability))
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(content("   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("content"));

        assertThat(commentRepository.count()).isZero();
    }

    @Test
    void aCommentOfAnotherVulnerabilityOrAnotherCompanyIsNotFound() throws Exception {
        long mine = comment(acme.admin, "Da Acme");
        long otherVulnerability = createVulnerability("Outra vulnerabilidade");

        // Right company, wrong parent: the path must match the row.
        mockMvc.perform(put(commentsOf(otherVulnerability) + "/" + mine)
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(content("Movido de discussão"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        TestDataFactory.Tenant globex = fixtures.tenant("globex");
        String intruder = fixtures.bearer(globex.admin);

        mockMvc.perform(get(commentsOf(vulnerability)).header("Authorization", intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(commentsOf(vulnerability) + "/" + mine).header("Authorization", intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(content("Sequestrado"))))
                .andExpect(status().isNotFound());

        assertThat(commentRepository.findById(mine).orElseThrow(AssertionError::new).getContent())
                .isEqualTo("Da Acme");
    }

    @Test
    void anUnknownVulnerabilityIsNotFound() throws Exception {
        mockMvc.perform(get(commentsOf(999_999L)).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- audit --------------------------------------------------------------

    /**
     * AuditSanitizer masks by key name, not by value, so a credential pasted into a comment
     * would land readable in the trail of every ADMIN. Only the length is recorded.
     */
    @Test
    void theTrailRecordsTheLengthOfTheCommentAndNeverItsText() throws Exception {
        String secret = "a senha de producao e hunter2-super-secreta";
        comment(acme.developer, secret);

        AuditLog entry = auditLogRepository.findAll().stream()
                .filter(log -> log.getAction() == AuditAction.COMMENT)
                .findFirst().orElseThrow(AssertionError::new);

        assertThat(entry.getEntityType()).isEqualTo("Comment");
        assertThat(entry.getCompanyId()).isEqualTo(acme.company.getId());
        assertThat(entry.getActorEmail()).isEqualTo("developer@acme.test");
        assertThat(entry.getNewValueJson())
                .contains("\"contentLength\":" + secret.length())
                .doesNotContain("hunter2")
                .doesNotContain("senha de producao");
    }

    // --- deletion of the parent ---------------------------------------------

    @Test
    void deletingTheVulnerabilityRemovesItsCommentsInsteadOfBlockingTheDeletion() throws Exception {
        comment(acme.admin, "Primeiro");
        comment(acme.developer, "Segundo");

        // No endpoint deletes a comment, so a 409 here would make this row undeletable.
        mockMvc.perform(delete(VULNERABILITIES + "/" + vulnerability)
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNoContent());

        assertThat(commentRepository.count()).isZero();
        assertThat(vulnerabilityRepository.count()).isZero();

        AuditLog deletion = auditLogRepository.findAll().stream()
                .filter(log -> log.getAction() == AuditAction.DELETE)
                .findFirst().orElseThrow(AssertionError::new);
        assertThat(deletion.getOldValueJson()).contains("\"commentCount\":2");
    }

    // --- helpers ------------------------------------------------------------

    private Map<String, Object> content(String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("content", text);
        return body;
    }

    private long comment(User author, String text) throws Exception {
        return idOf(mockMvc.perform(post(commentsOf(vulnerability))
                        .header("Authorization", fixtures.bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(content(text))))
                .andExpect(status().isCreated()));
    }

    private long createVulnerability(String title) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("assetId", api);
        body.put("title", title);
        body.put("severity", Severity.HIGH.name());
        return idOf(mockMvc.perform(post(VULNERABILITIES)
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated()));
    }

    private long createProject(String name) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        return idOf(mockMvc.perform(post(PROJECTS)
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated()));
    }

    private long createAsset(long projectId, String name) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", projectId);
        body.put("name", name);
        body.put("type", AssetType.API.name());
        body.put("environment", Environment.PRODUCTION.name());
        body.put("criticality", Criticality.HIGH.name());
        return idOf(mockMvc.perform(post(ASSETS)
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated()));
    }

    private long idOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
