package com.securityhub.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securityhub.asset.dto.AssetRequest;
import com.securityhub.asset.dto.AssetResponse;
import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.company.Company;
import com.securityhub.project.Project;
import com.securityhub.project.ProjectRepository;
import com.securityhub.project.ProjectStatus;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.error.ConflictException;
import com.securityhub.shared.error.NotFoundException;
import com.securityhub.shared.web.PageableSupport;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long OTHER_COMPANY_ID = 2L;
    private static final Long PROJECT_ID = 30L;
    private static final Long OTHER_PROJECT_ID = 31L;
    private static final Long ASSET_ID = 77L;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AuditService auditService;

    private AssetService assetService;
    private Company company;
    private User admin;
    private Project project;
    private AuthenticatedUser current;

    @BeforeEach
    void setUp() {
        assetService = new AssetService(assetRepository, projectRepository, auditService);
        company = new Company("Acme", "acme");
        ReflectionTestUtils.setField(company, "id", COMPANY_ID);
        admin = new User(company, "Administradora", "admin@acme.test", "hash", Role.ADMIN);
        ReflectionTestUtils.setField(admin, "id", 10L);
        current = AuthenticatedUser.from(admin);
        project = project(PROJECT_ID, "Portal");
    }

    // --- create -------------------------------------------------------------

    @Test
    void createPersistsAssetScopedToTheProjectCompany() {
        stubProject(project);
        stubSave();
        when(assetRepository.existsByProjectIdAndIdentifierIgnoreCase(PROJECT_ID, "api.acme.test"))
                .thenReturn(false);

        AssetResponse response = assetService.create(current,
                request(PROJECT_ID, "  API de Cobrança  ", "  Fatura  ", AssetType.API, "  api.acme.test  ",
                        Environment.PRODUCTION, Criticality.HIGH));

        ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(captor.capture());
        Asset saved = captor.getValue();

        assertThat(saved.getCompany().getId()).isEqualTo(COMPANY_ID);
        assertThat(saved.getProject()).isSameAs(project);
        assertThat(saved.getName()).isEqualTo("API de Cobrança");
        assertThat(saved.getDescription()).isEqualTo("Fatura");
        assertThat(saved.getIdentifier()).isEqualTo("api.acme.test");
        assertThat(saved.getType()).isEqualTo(AssetType.API);
        assertThat(saved.getEnvironment()).isEqualTo(Environment.PRODUCTION);
        assertThat(saved.getCriticality()).isEqualTo(Criticality.HIGH);

        assertThat(response.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(response.getProjectName()).isEqualTo("Portal");
        assertThat(response.getVulnerabilityCount()).isZero();
    }

    @Test
    void createNormalizesABlankIdentifierToNull() {
        stubProject(project);
        stubSave();

        AssetResponse response = assetService.create(current,
                request(PROJECT_ID, "Servidor", null, AssetType.SERVER, "   ",
                        Environment.STAGING, Criticality.LOW));

        assertThat(response.getIdentifier()).isNull();
        assertThat(response.getDescription()).isNull();
        // A missing identifier can never conflict, so the uniqueness check is not even run.
        verify(assetRepository, never()).existsByProjectIdAndIdentifierIgnoreCase(anyLong(), anyString());
    }

    @Test
    void createRejectsDuplicatedIdentifierInTheSameProject() {
        stubProject(project);
        when(assetRepository.existsByProjectIdAndIdentifierIgnoreCase(PROJECT_ID, "api.acme.test"))
                .thenReturn(true);

        assertThatThrownBy(() -> assetService.create(current,
                request(PROJECT_ID, "API", null, AssetType.API, "api.acme.test",
                        Environment.PRODUCTION, Criticality.HIGH)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("identificador");

        verify(assetRepository, never()).save(any());
        verify(auditService, never()).record(any());
    }

    /** the referenced project must belong to the company of the principal. */
    @Test
    void createReturnsNotFoundWhenTheProjectBelongsToAnotherCompany() {
        when(projectRepository.findByIdAndCompanyId(OTHER_PROJECT_ID, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assetService.create(current,
                request(OTHER_PROJECT_ID, "API", null, AssetType.API, null,
                        Environment.PRODUCTION, Criticality.HIGH)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Projeto " + OTHER_PROJECT_ID);

        // 404 and not 403: the response must not confirm that the id exists elsewhere.
        verify(projectRepository).findByIdAndCompanyId(OTHER_PROJECT_ID, COMPANY_ID);
        verify(projectRepository, never()).findById(any());
        verify(assetRepository, never()).save(any());
    }

    @Test
    void createRecordsAuditUnderTheAssetEntityType() {
        stubProject(project);
        stubSave();

        assetService.create(current, request(PROJECT_ID, "API", "Descrição", AssetType.API, "api.acme.test",
                Environment.PRODUCTION, Criticality.CRITICAL));

        AuditEntry entry = captureAudit();
        assertThat(entry.getEntityType()).isEqualTo("Asset");
        assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(entry.getCompanyId()).isEqualTo(COMPANY_ID);
        assertThat(entry.getActorId()).isEqualTo(admin.getId());
        assertThat(entry.getEntityId()).isEqualTo(ASSET_ID);
        assertThat(entry.getOldValue()).isNull();
        assertThat(entry.getNewValue())
                .containsEntry("name", "API")
                .containsEntry("description", "Descrição")
                .containsEntry("type", "API")
                .containsEntry("identifier", "api.acme.test")
                .containsEntry("environment", "PRODUCTION")
                .containsEntry("criticality", "CRITICAL")
                .containsEntry("projectId", PROJECT_ID);
    }

    // --- get / tenant isolation ---------------------------------------------

    @Test
    void getReturnsNotFoundWhenTheAssetBelongsToAnotherCompany() {
        when(assetRepository.findByIdAndCompanyId(7L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assetService.get(current, 7L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Ativo 7");
    }

    @Test
    void getAlwaysQueriesWithTheCompanyOfThePrincipal() {
        AuthenticatedUser intruder = new AuthenticatedUser(50L, OTHER_COMPANY_ID, "x@other.test",
                "Intrusa", Role.ADMIN, true);
        when(assetRepository.findByIdAndCompanyId(7L, OTHER_COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assetService.get(intruder, 7L)).isInstanceOf(NotFoundException.class);

        verify(assetRepository).findByIdAndCompanyId(7L, OTHER_COMPANY_ID);
        verify(assetRepository, never()).findById(any());
    }

    @Test
    void updateReturnsNotFoundForAnUnknownAsset() {
        when(assetRepository.findByIdAndCompanyId(7L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assetService.update(current, 7L,
                request(PROJECT_ID, "API", null, AssetType.API, null, Environment.TEST, Criticality.LOW)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteReturnsNotFoundForAnUnknownAsset() {
        when(assetRepository.findByIdAndCompanyId(7L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assetService.delete(current, 7L)).isInstanceOf(NotFoundException.class);

        verify(assetRepository, never()).delete(any());
    }

    // --- update / delete ----------------------------------------------------

    @Test
    void updateChangesFieldsAndAuditsBeforeAndAfter() {
        Asset asset = existingAsset("API", "Antiga", AssetType.API, "api.acme.test",
                Environment.STAGING, Criticality.LOW);
        when(assetRepository.findByIdAndCompanyId(ASSET_ID, COMPANY_ID)).thenReturn(Optional.of(asset));

        AssetResponse response = assetService.update(current, ASSET_ID,
                request(PROJECT_ID, "API v2", "Nova", AssetType.WEBSITE, null,
                        Environment.PRODUCTION, Criticality.CRITICAL));

        assertThat(response.getName()).isEqualTo("API v2");
        assertThat(response.getType()).isEqualTo(AssetType.WEBSITE);
        assertThat(response.getIdentifier()).isNull();

        AuditEntry entry = captureAudit();
        assertThat(entry.getEntityType()).isEqualTo("Asset");
        assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
        assertThat(entry.getOldValue())
                .containsEntry("name", "API")
                .containsEntry("identifier", "api.acme.test")
                .containsEntry("environment", "STAGING");
        assertThat(entry.getNewValue())
                .containsEntry("name", "API v2")
                .containsEntry("identifier", null)
                .containsEntry("environment", "PRODUCTION");
    }

    @Test
    void updateMovesTheAssetToAnotherProjectOfTheSameCompany() {
        Asset asset = existingAsset("API", null, AssetType.API, "api.acme.test",
                Environment.PRODUCTION, Criticality.HIGH);
        Project target = project(OTHER_PROJECT_ID, "Intranet");
        when(assetRepository.findByIdAndCompanyId(ASSET_ID, COMPANY_ID)).thenReturn(Optional.of(asset));
        stubProject(target);
        // The uniqueness check must run against the target project, not the current one.
        when(assetRepository.existsByProjectIdAndIdentifierIgnoreCaseAndIdNot(
                OTHER_PROJECT_ID, "api.acme.test", ASSET_ID)).thenReturn(false);

        AssetResponse response = assetService.update(current, ASSET_ID,
                request(OTHER_PROJECT_ID, "API", null, AssetType.API, "api.acme.test",
                        Environment.PRODUCTION, Criticality.HIGH));

        assertThat(asset.getProject()).isSameAs(target);
        assertThat(response.getProjectId()).isEqualTo(OTHER_PROJECT_ID);
        assertThat(response.getProjectName()).isEqualTo("Intranet");
    }

    @Test
    void updateRefusesToMoveTheAssetIntoAnotherCompanyProject() {
        Asset asset = existingAsset("API", null, AssetType.API, null,
                Environment.PRODUCTION, Criticality.HIGH);
        when(assetRepository.findByIdAndCompanyId(ASSET_ID, COMPANY_ID)).thenReturn(Optional.of(asset));
        when(projectRepository.findByIdAndCompanyId(OTHER_PROJECT_ID, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assetService.update(current, ASSET_ID,
                request(OTHER_PROJECT_ID, "API", null, AssetType.API, null,
                        Environment.PRODUCTION, Criticality.HIGH)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Projeto " + OTHER_PROJECT_ID);

        assertThat(asset.getProject()).isSameAs(project);
        verify(assetRepository, never()).save(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void updateRejectsAnIdentifierAlreadyUsedInTheSameProject() {
        Asset asset = existingAsset("API", null, AssetType.API, "api.acme.test",
                Environment.PRODUCTION, Criticality.HIGH);
        when(assetRepository.findByIdAndCompanyId(ASSET_ID, COMPANY_ID)).thenReturn(Optional.of(asset));
        when(assetRepository.existsByProjectIdAndIdentifierIgnoreCaseAndIdNot(
                PROJECT_ID, "outro.acme.test", ASSET_ID)).thenReturn(true);

        assertThatThrownBy(() -> assetService.update(current, ASSET_ID,
                request(PROJECT_ID, "API", null, AssetType.API, "outro.acme.test",
                        Environment.PRODUCTION, Criticality.HIGH)))
                .isInstanceOf(ConflictException.class);

        verify(assetRepository, never()).save(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void deleteRemovesTheAssetAndAuditsTheRemovedValues() {
        Asset asset = existingAsset("API", "Antiga", AssetType.API, "api.acme.test",
                Environment.PRODUCTION, Criticality.HIGH);
        when(assetRepository.findByIdAndCompanyId(ASSET_ID, COMPANY_ID)).thenReturn(Optional.of(asset));

        assetService.delete(current, ASSET_ID);

        verify(assetRepository).delete(asset);

        AuditEntry entry = captureAudit();
        assertThat(entry.getEntityType()).isEqualTo("Asset");
        assertThat(entry.getAction()).isEqualTo(AuditAction.DELETE);
        assertThat(entry.getEntityId()).isEqualTo(ASSET_ID);
        assertThat(entry.getNewValue()).isNull();
        assertThat(entry.getOldValue()).containsEntry("name", "API");
    }

    // --- search -------------------------------------------------------------

    @Test
    void searchDropsASortPropertyThatIsNotWhitelisted() {
        stubEmptySearch();

        assetService.search(current, null, null, null, null, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "company.name")));

        assertThat(capturePageable().getSort()).isEqualTo(AssetService.DEFAULT_SORT);
    }

    @Test
    void searchKeepsAWhitelistedSortProperty() {
        stubEmptySearch();

        assetService.search(current, null, null, null, null, null,
                PageRequest.of(2, 15, Sort.by(Sort.Direction.ASC, "criticality")));

        Pageable used = capturePageable();
        assertThat(used.getPageNumber()).isEqualTo(2);
        assertThat(used.getPageSize()).isEqualTo(15);
        assertThat(used.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "criticality"));
    }

    @Test
    void searchCapsThePageSize() {
        stubEmptySearch();

        assetService.search(current, null, null, null, null, null, PageRequest.of(0, 5000));

        assertThat(capturePageable().getPageSize()).isEqualTo(PageableSupport.MAX_PAGE_SIZE);
    }

    @Test
    void searchAlwaysBuildsATenantScopedSpecification() {
        stubEmptySearch();

        assetService.search(current, "  API  ", PROJECT_ID, AssetType.API, Environment.PRODUCTION,
                Criticality.HIGH, PageRequest.of(0, 20));

        ArgumentCaptor<Specification<Asset>> captor = specificationCaptor();
        verify(assetRepository).findAll(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isNotNull();
    }

    // --- helpers ------------------------------------------------------------

    private void stubProject(Project target) {
        when(projectRepository.findByIdAndCompanyId(target.getId(), COMPANY_ID))
                .thenReturn(Optional.of(target));
    }

    private void stubSave() {
        when(assetRepository.save(any(Asset.class))).thenAnswer(invocation -> {
            Asset asset = invocation.getArgument(0);
            ReflectionTestUtils.setField(asset, "id", ASSET_ID);
            return asset;
        });
    }

    private void stubEmptySearch() {
        when(assetRepository.findAll(ArgumentMatchers.<Specification<Asset>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(assetRepository).findAll(ArgumentMatchers.<Specification<Asset>>any(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Specification<Asset>> specificationCaptor() {
        return (ArgumentCaptor<Specification<Asset>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(Specification.class);
    }

    private AuditEntry captureAudit() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(captor.capture());
        return captor.getValue();
    }

    private Project project(Long id, String name) {
        Project created = new Project(company, name, null, ProjectStatus.ACTIVE, admin);
        ReflectionTestUtils.setField(created, "id", id);
        return created;
    }

    private Asset existingAsset(String name, String description, AssetType type, String identifier,
                                Environment environment, Criticality criticality) {
        Asset asset = new Asset(company, project, name, description, type, identifier, environment, criticality);
        ReflectionTestUtils.setField(asset, "id", ASSET_ID);
        return asset;
    }

    private AssetRequest request(Long projectId, String name, String description, AssetType type,
                                 String identifier, Environment environment, Criticality criticality) {
        AssetRequest request = new AssetRequest();
        request.setProjectId(projectId);
        request.setName(name);
        request.setDescription(description);
        request.setType(type);
        request.setIdentifier(identifier);
        request.setEnvironment(environment);
        request.setCriticality(criticality);
        return request;
    }
}
