package com.securityhub.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.user.User;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ProjectIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/projects";

    @Autowired
    private ProjectRepository projectRepository;

    private TestDataFactory.Tenant acme;

    @BeforeEach
    void createTenant() {
        acme = fixtures.tenant("acme");
    }

    // --- happy path ---------------------------------------------------------

    @Test
    void adminRunsTheFullCrudLifecycle() throws Exception {
        String created = mockMvc.perform(post(BASE)
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("Portal Cliente", "Portal público da Acme", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Portal Cliente"))
                .andExpect(jsonPath("$.description").value("Portal público da Acme"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.assetCount").value(0))
                .andExpect(jsonPath("$.createdByName").value("admin"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(get(BASE + "/" + id).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Portal Cliente"));

        mockMvc.perform(put(BASE + "/" + id)
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("Portal Cliente V2", "Nova descrição", ProjectStatus.ARCHIVED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Portal Cliente V2"))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(delete(BASE + "/" + id).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE + "/" + id).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(projectRepository.count()).isZero();
    }

    @Test
    void theProjectIsAlwaysBoundToTheCompanyOfTheToken() throws Exception {
        TestDataFactory.Tenant other = fixtures.tenant("globex");

        Map<String, Object> body = payload("Portal", null, null);
        // A client-supplied tenant must be ignored: companyId comes from the principal only.
        body.put("companyId", other.company.getId());

        String created = mockMvc.perform(post(BASE)
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(created).get("id").asLong();
        assertThat(projectRepository.findByIdAndCompanyId(id, acme.company.getId())).isPresent();
        assertThat(projectRepository.findByIdAndCompanyId(id, other.company.getId())).isNotPresent();
    }

    // --- authentication -----------------------------------------------------

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        long id = createProject(acme.admin, "Portal");

        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(BASE + "/" + id)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("Outro", null, null))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("Outro", null, null))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE + "/" + id)).andExpect(status().isUnauthorized());
    }

    // --- role matrix (docs/permissions.md) -------------------------------------------

    @Test
    void analystDeveloperAndViewerCanReadButNotWrite() throws Exception {
        long id = createProject(acme.admin, "Portal");

        for (User reader : new User[]{acme.analyst, acme.developer, acme.viewer}) {
            String bearer = fixtures.bearer(reader);

            mockMvc.perform(get(BASE).header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].name").value("Portal"));
            mockMvc.perform(get(BASE + "/" + id).header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id));

            mockMvc.perform(post(BASE).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(payload("Novo de " + reader.getRole(), null, null))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            mockMvc.perform(put(BASE + "/" + id).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(payload("Renomeado", null, null))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(delete(BASE + "/" + id).header("Authorization", bearer))
                    .andExpect(status().isForbidden());
        }

        assertThat(projectRepository.count()).isEqualTo(1);
        assertThat(projectRepository.findById(id).orElseThrow(AssertionError::new).getName()).isEqualTo("Portal");
    }

    // --- tenant isolation ---------------------------------------------------

    @Test
    void anotherCompanyCannotSeeOrTouchTheProject() throws Exception {
        long id = createProject(acme.admin, "Portal Acme");
        TestDataFactory.Tenant globex = fixtures.tenant("globex");
        String intruder = fixtures.bearer(globex.admin);

        // 404 and not 403: existence must not leak across tenants.
        mockMvc.perform(get(BASE + "/" + id).header("Authorization", intruder))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(put(BASE + "/" + id).header("Authorization", intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("Sequestrado", null, null))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete(BASE + "/" + id).header("Authorization", intruder))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(BASE).header("Authorization", intruder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));

        Project untouched = projectRepository.findByIdAndCompanyId(id, acme.company.getId())
                .orElseThrow(AssertionError::new);
        assertThat(untouched.getName()).isEqualTo("Portal Acme");
        assertThat(projectRepository.findByIdAndCompanyId(id, globex.company.getId())).isNotPresent();
    }

    @Test
    void theSameNameIsAllowedInAnotherCompany() throws Exception {
        createProject(acme.admin, "Portal");
        TestDataFactory.Tenant globex = fixtures.tenant("globex");

        mockMvc.perform(post(BASE).header("Authorization", fixtures.bearer(globex.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("Portal", null, null))))
                .andExpect(status().isCreated());
    }

    // --- validation and conflicts -------------------------------------------

    @Test
    void invalidPayloadReturnsTheValidationErrorEnvelope() throws Exception {
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("name", "A");
        invalid.put("description", repeat("x", 2001));

        mockMvc.perform(post(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("name", "description")))
                .andExpect(jsonPath("$.path").value(BASE))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void blankNameIsRejected() throws Exception {
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("name", "   ");

        mockMvc.perform(post(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void duplicatedNameInTheSameCompanyReturnsConflict() throws Exception {
        createProject(acme.admin, "Portal");

        mockMvc.perform(post(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("Portal", null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void duplicatedNameIgnoresCase() throws Exception {
        createProject(acme.admin, "Portal");

        mockMvc.perform(post(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("pOrTaL", null, null))))
                .andExpect(status().isConflict());
    }

    @Test
    void renamingOntoAnExistingNameReturnsConflictButKeepingItsOwnNameDoesNot() throws Exception {
        createProject(acme.admin, "Portal");
        long second = createProject(acme.admin, "Intranet");

        mockMvc.perform(put(BASE + "/" + second).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("Portal", null, null))))
                .andExpect(status().isConflict());

        mockMvc.perform(put(BASE + "/" + second).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("Intranet", "Somente a descrição mudou", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Somente a descrição mudou"));
    }

    @Test
    void anUnknownStatusIsRejectedWithBadRequest() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .param("status", "NAO_EXISTE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // --- listing: pagination, sorting and filters ---------------------------

    @Test
    void listReturnsTheStandardPagedEnvelope() throws Exception {
        createProject(acme.admin, "Alpha");
        createProject(acme.admin, "Bravo");
        createProject(acme.admin, "Charlie");

        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .param("page", "0").param("size", "2").param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Alpha"))
                .andExpect(jsonPath("$.content[1].name").value("Bravo"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.sort").value("name,asc"));

        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .param("page", "1").param("size", "2").param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Charlie"))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void listFallsBackToTheDefaultOrderWhenSortIsNotWhitelisted() throws Exception {
        createProject(acme.admin, "Alpha");
        createProject(acme.admin, "Bravo");

        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .param("sort", "passwordHash,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sort").value("createdAt,desc"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void listCapsThePageSize() throws Exception {
        createProject(acme.admin, "Alpha");

        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void listFiltersBySearchTermAndStatus() throws Exception {
        createProject(acme.admin, "Portal Cliente");
        long intranet = createProject(acme.admin, "Intranet");

        mockMvc.perform(put(BASE + "/" + intranet).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload("Intranet", "Sistema interno", ProjectStatus.ARCHIVED))))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .param("search", "PORTAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Portal Cliente"));

        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .param("search", "interno"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Intranet"));

        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .param("status", "ARCHIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Intranet"));

        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Portal Cliente"));
    }

    @Test
    void listIsEmptyWhenTheCompanyHasNoProjects() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    // --- helpers ------------------------------------------------------------

    private long createProject(User admin, String name) throws Exception {
        ResultActions result = mockMvc.perform(post(BASE)
                        .header("Authorization", fixtures.bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(name, null, null))))
                .andExpect(status().isCreated());
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private Map<String, Object> payload(String name, String description, ProjectStatus status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        if (description != null) {
            payload.put("description", description);
        }
        if (status != null) {
            payload.put("status", status.name());
        }
        return payload;
    }

    private String repeat(String value, int times) {
        StringBuilder builder = new StringBuilder(value.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
