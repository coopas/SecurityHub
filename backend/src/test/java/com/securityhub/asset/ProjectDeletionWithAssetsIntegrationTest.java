package com.securityhub.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securityhub.project.ProjectRepository;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * "deleting projects and assets that have children must return a conflict, not cascade
 * silently" (docs/permissions.md). Lives in the assets test package because the rule only
 * becomes observable once assets exist.
 */
class ProjectDeletionWithAssetsIntegrationTest extends AbstractIntegrationTest {

    private static final String PROJECTS = "/api/v1/projects";
    private static final String ASSETS = "/api/v1/assets";

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AssetRepository assetRepository;

    private TestDataFactory.Tenant acme;

    @BeforeEach
    void createTenant() {
        acme = fixtures.tenant("acme");
    }

    @Test
    void deletingAProjectWithAssetsReturnsConflictAndKeepsEverything() throws Exception {
        long project = createProject("Portal");
        long asset = createAsset(project, "Servidor");

        mockMvc.perform(delete(PROJECTS + "/" + project).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.status").value(409));

        // Nothing may have been cascaded away by the failed attempt.
        assertThat(projectRepository.findById(project)).isPresent();
        assertThat(assetRepository.findById(asset)).isPresent();
    }

    @Test
    void theProjectIsDeletedOnceItsAssetsAreGone() throws Exception {
        long project = createProject("Portal");
        long asset = createAsset(project, "Servidor");

        mockMvc.perform(delete(ASSETS + "/" + asset).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete(PROJECTS + "/" + project).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNoContent());

        assertThat(projectRepository.count()).isZero();
        assertThat(assetRepository.count()).isZero();
    }

    @Test
    void theProjectListingReportsTheNumberOfAssets() throws Exception {
        long project = createProject("Portal");
        createProject("Intranet");
        createAsset(project, "Servidor 1");
        createAsset(project, "Servidor 2");

        mockMvc.perform(get(PROJECTS).header("Authorization", fixtures.bearer(acme.admin))
                        .param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Intranet"))
                .andExpect(jsonPath("$.content[0].assetCount").value(0))
                .andExpect(jsonPath("$.content[1].name").value("Portal"))
                .andExpect(jsonPath("$.content[1].assetCount").value(2));

        mockMvc.perform(get(PROJECTS + "/" + project).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetCount").value(2));
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
        body.put("type", AssetType.SERVER.name());
        body.put("environment", Environment.PRODUCTION.name());
        body.put("criticality", Criticality.MEDIUM.name());
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
