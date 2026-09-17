package com.securityhub.asset;

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

class AssetIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/assets";
    private static final String PROJECTS = "/api/v1/projects";

    @Autowired
    private AssetRepository assetRepository;

    private TestDataFactory.Tenant acme;
    private long portal;

    @BeforeEach
    void createTenantAndProject() throws Exception {
        acme = fixtures.tenant("acme");
        portal = createProject(acme.admin, "Portal");
    }

    // --- happy path ---------------------------------------------------------

    @Test
    void adminRunsTheFullCrudLifecycle() throws Exception {
        Map<String, Object> body = payload(portal, "API de Cobrança", AssetType.API,
                Environment.PRODUCTION, Criticality.HIGH);
        body.put("description", "Serviço de faturamento");
        body.put("identifier", "api.acme.test");

        String created = mockMvc.perform(post(BASE)
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("API de Cobrança"))
                .andExpect(jsonPath("$.description").value("Serviço de faturamento"))
                .andExpect(jsonPath("$.type").value("API"))
                .andExpect(jsonPath("$.identifier").value("api.acme.test"))
                .andExpect(jsonPath("$.environment").value("PRODUCTION"))
                .andExpect(jsonPath("$.criticality").value("HIGH"))
                .andExpect(jsonPath("$.projectId").value(portal))
                .andExpect(jsonPath("$.projectName").value("Portal"))
                .andExpect(jsonPath("$.vulnerabilityCount").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(get(BASE + "/" + id).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("API de Cobrança"));

        Map<String, Object> update = payload(portal, "API de Cobrança v2", AssetType.WEBSITE,
                Environment.STAGING, Criticality.CRITICAL);
        update.put("identifier", "portal.acme.test");

        mockMvc.perform(put(BASE + "/" + id)
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("API de Cobrança v2"))
                .andExpect(jsonPath("$.type").value("WEBSITE"))
                .andExpect(jsonPath("$.environment").value("STAGING"))
                .andExpect(jsonPath("$.criticality").value("CRITICAL"))
                .andExpect(jsonPath("$.identifier").value("portal.acme.test"));

        mockMvc.perform(delete(BASE + "/" + id).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE + "/" + id).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(assetRepository.count()).isZero();
    }

    @Test
    void theAssetIsAlwaysBoundToTheCompanyOfTheToken() throws Exception {
        TestDataFactory.Tenant other = fixtures.tenant("globex");

        Map<String, Object> body = payload(portal, "Servidor", AssetType.SERVER,
                Environment.PRODUCTION, Criticality.MEDIUM);
        // A client-supplied tenant must be ignored: companyId comes from the principal only.
        body.put("companyId", other.company.getId());

        String created = mockMvc.perform(post(BASE)
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(created).get("id").asLong();
        assertThat(assetRepository.findByIdAndCompanyId(id, acme.company.getId())).isPresent();
        assertThat(assetRepository.findByIdAndCompanyId(id, other.company.getId())).isNotPresent();
    }

    @Test
    void anAssetWithoutIdentifierIsAccepted() throws Exception {
        createAsset(acme.admin, portal, "Estação 1", null);
        createAsset(acme.admin, portal, "Estação 2", "   ");

        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].identifier").doesNotExist());
    }

    // --- authentication -----------------------------------------------------

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        long id = createAsset(acme.admin, portal, "Servidor", "srv-01");

        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(BASE + "/" + id)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(portal, "Outro", AssetType.OTHER,
                                Environment.TEST, Criticality.LOW))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(portal, "Outro", AssetType.OTHER,
                                Environment.TEST, Criticality.LOW))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(BASE + "/" + id)).andExpect(status().isUnauthorized());
    }

    // --- role matrix (docs/permissions.md) -------------------------------------------

    @Test
    void analystDeveloperAndViewerCanReadButNotWrite() throws Exception {
        long id = createAsset(acme.admin, portal, "Servidor", "srv-01");

        for (User reader : new User[]{acme.analyst, acme.developer, acme.viewer}) {
            String bearer = fixtures.bearer(reader);

            mockMvc.perform(get(BASE).header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].name").value("Servidor"));
            mockMvc.perform(get(BASE + "/" + id).header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id));

            mockMvc.perform(post(BASE).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(payload(portal, "Novo de " + reader.getRole(), AssetType.OTHER,
                                    Environment.TEST, Criticality.LOW))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            mockMvc.perform(put(BASE + "/" + id).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(payload(portal, "Renomeado", AssetType.OTHER,
                                    Environment.TEST, Criticality.LOW))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(delete(BASE + "/" + id).header("Authorization", bearer))
                    .andExpect(status().isForbidden());
        }

        assertThat(assetRepository.count()).isEqualTo(1);
        assertThat(assetRepository.findById(id).orElseThrow(AssertionError::new).getName()).isEqualTo("Servidor");
    }

    // --- tenant isolation ---------------------------------------------------

    @Test
    void anotherCompanyCannotSeeOrTouchTheAsset() throws Exception {
        long id = createAsset(acme.admin, portal, "Servidor Acme", "srv-01");
        TestDataFactory.Tenant globex = fixtures.tenant("globex");
        String intruder = fixtures.bearer(globex.admin);
        long globexProject = createProject(globex.admin, "Portal Globex");

        // 404 and not 403: existence must not leak across tenants.
        mockMvc.perform(get(BASE + "/" + id).header("Authorization", intruder))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(put(BASE + "/" + id).header("Authorization", intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(globexProject, "Sequestrado", AssetType.OTHER,
                                Environment.TEST, Criticality.LOW))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete(BASE + "/" + id).header("Authorization", intruder))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(BASE).header("Authorization", intruder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));

        Asset untouched = assetRepository.findByIdAndCompanyId(id, acme.company.getId())
                .orElseThrow(AssertionError::new);
        assertThat(untouched.getName()).isEqualTo("Servidor Acme");
        assertThat(assetRepository.findByIdAndCompanyId(id, globex.company.getId())).isNotPresent();
    }

    /** the project reference is validated against the company of the principal. */
    @Test
    void creatingAnAssetInAnotherCompanyProjectReturnsNotFound() throws Exception {
        TestDataFactory.Tenant globex = fixtures.tenant("globex");
        long globexProject = createProject(globex.admin, "Portal Globex");

        mockMvc.perform(post(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(globexProject, "Invasor", AssetType.SERVER,
                                Environment.PRODUCTION, Criticality.HIGH))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(assetRepository.count()).isZero();
    }

    @Test
    void movingAnAssetToAnotherCompanyProjectReturnsNotFound() throws Exception {
        long id = createAsset(acme.admin, portal, "Servidor", "srv-01");
        TestDataFactory.Tenant globex = fixtures.tenant("globex");
        long globexProject = createProject(globex.admin, "Portal Globex");

        mockMvc.perform(put(BASE + "/" + id).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(globexProject, "Servidor", AssetType.SERVER,
                                Environment.PRODUCTION, Criticality.HIGH))))
                .andExpect(status().isNotFound());

        // Read back through the API: the asset must still hang from its original project.
        mockMvc.perform(get(BASE + "/" + id).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(portal));
    }

    @Test
    void anAssetCanBeMovedToAnotherProjectOfTheSameCompany() throws Exception {
        long id = createAsset(acme.admin, portal, "Servidor", "srv-01");
        long intranet = createProject(acme.admin, "Intranet");

        Map<String, Object> body = payload(intranet, "Servidor", AssetType.SERVER,
                Environment.PRODUCTION, Criticality.HIGH);
        body.put("identifier", "srv-01");

        mockMvc.perform(put(BASE + "/" + id).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(intranet))
                .andExpect(jsonPath("$.projectName").value("Intranet"));
    }

    // --- validation and conflicts -------------------------------------------

    @Test
    void invalidPayloadReturnsTheValidationErrorEnvelope() throws Exception {
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("name", "A");

        mockMvc.perform(post(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        hasItems("name", "projectId", "type", "environment", "criticality")))
                .andExpect(jsonPath("$.path").value(BASE))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void duplicatedIdentifierInTheSameProjectReturnsConflict() throws Exception {
        createAsset(acme.admin, portal, "Servidor 1", "srv-01");

        Map<String, Object> body = payload(portal, "Servidor 2", AssetType.SERVER,
                Environment.PRODUCTION, Criticality.LOW);
        body.put("identifier", "SRV-01");

        mockMvc.perform(post(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void theSameIdentifierIsAllowedInAnotherProject() throws Exception {
        createAsset(acme.admin, portal, "Servidor 1", "srv-01");
        long intranet = createProject(acme.admin, "Intranet");

        Map<String, Object> body = payload(intranet, "Servidor 2", AssetType.SERVER,
                Environment.PRODUCTION, Criticality.LOW);
        body.put("identifier", "srv-01");

        mockMvc.perform(post(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.identifier").value("srv-01"));
    }

    @Test
    void renamingTheIdentifierOntoAnExistingOneConflictsButKeepingItDoesNot() throws Exception {
        createAsset(acme.admin, portal, "Servidor 1", "srv-01");
        long second = createAsset(acme.admin, portal, "Servidor 2", "srv-02");

        Map<String, Object> collision = payload(portal, "Servidor 2", AssetType.SERVER,
                Environment.PRODUCTION, Criticality.LOW);
        collision.put("identifier", "srv-01");

        mockMvc.perform(put(BASE + "/" + second).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(collision)))
                .andExpect(status().isConflict());

        Map<String, Object> own = payload(portal, "Servidor 2 renomeado", AssetType.SERVER,
                Environment.PRODUCTION, Criticality.LOW);
        own.put("identifier", "srv-02");

        mockMvc.perform(put(BASE + "/" + second).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(own)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Servidor 2 renomeado"));
    }

    @Test
    void anUnknownEnumFilterIsRejectedWithBadRequest() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .param("type", "NAO_EXISTE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void anUnknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(post(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload(999_999L, "Fantasma", AssetType.OTHER,
                                Environment.TEST, Criticality.LOW))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- listing: pagination, sorting and filters ---------------------------

    @Test
    void listReturnsTheStandardPagedEnvelope() throws Exception {
        createAsset(acme.admin, portal, "Alpha", null);
        createAsset(acme.admin, portal, "Bravo", null);
        createAsset(acme.admin, portal, "Charlie", null);

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
        createAsset(acme.admin, portal, "Alpha", null);
        createAsset(acme.admin, portal, "Bravo", null);

        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .param("sort", "company.name,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sort").value("createdAt,desc"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void listCapsThePageSize() throws Exception {
        createAsset(acme.admin, portal, "Alpha", null);

        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.admin))
                        .param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void listFiltersByProjectTypeEnvironmentCriticalityAndSearch() throws Exception {
        long intranet = createProject(acme.admin, "Intranet");

        Map<String, Object> api = payload(portal, "API de Cobrança", AssetType.API,
                Environment.PRODUCTION, Criticality.CRITICAL);
        api.put("identifier", "api.acme.test");
        api.put("description", "Serviço externo");
        create(acme.admin, api);

        Map<String, Object> workstation = payload(intranet, "Notebook RH", AssetType.WORKSTATION,
                Environment.DEVELOPMENT, Criticality.LOW);
        workstation.put("description", "Equipamento interno");
        create(acme.admin, workstation);

        String bearer = fixtures.bearer(acme.admin);

        mockMvc.perform(get(BASE).header("Authorization", bearer).param("projectId", String.valueOf(portal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("API de Cobrança"));

        mockMvc.perform(get(BASE).header("Authorization", bearer).param("type", "WORKSTATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Notebook RH"));

        mockMvc.perform(get(BASE).header("Authorization", bearer).param("environment", "PRODUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("API de Cobrança"));

        mockMvc.perform(get(BASE).header("Authorization", bearer).param("criticality", "LOW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Notebook RH"));

        // The search term covers name, description and identifier.
        mockMvc.perform(get(BASE).header("Authorization", bearer).param("search", "COBRANÇA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("API de Cobrança"));

        mockMvc.perform(get(BASE).header("Authorization", bearer).param("search", "interno"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Notebook RH"));

        mockMvc.perform(get(BASE).header("Authorization", bearer).param("search", "api.acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("API de Cobrança"));

        // Filters combine instead of replacing each other.
        mockMvc.perform(get(BASE).header("Authorization", bearer)
                        .param("projectId", String.valueOf(portal))
                        .param("criticality", "LOW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listIsEmptyWhenTheCompanyHasNoAssets() throws Exception {
        mockMvc.perform(get(BASE).header("Authorization", fixtures.bearer(acme.viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    // --- helpers ------------------------------------------------------------

    private long createProject(User admin, String name) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        ResultActions result = mockMvc.perform(post(PROJECTS)
                        .header("Authorization", fixtures.bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated());
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private long createAsset(User admin, long projectId, String name, String identifier) throws Exception {
        Map<String, Object> body = payload(projectId, name, AssetType.SERVER,
                Environment.PRODUCTION, Criticality.MEDIUM);
        if (identifier != null) {
            body.put("identifier", identifier);
        }
        return create(admin, body);
    }

    private long create(User admin, Map<String, Object> body) throws Exception {
        ResultActions result = mockMvc.perform(post(BASE)
                        .header("Authorization", fixtures.bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated());
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private Map<String, Object> payload(long projectId, String name, AssetType type,
                                        Environment environment, Criticality criticality) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", projectId);
        payload.put("name", name);
        payload.put("type", type.name());
        payload.put("environment", environment.name());
        payload.put("criticality", criticality.name());
        return payload;
    }
}
