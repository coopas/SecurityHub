package com.securityhub.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.company.Company;
import com.securityhub.company.CompanyRepository;
import com.securityhub.project.dto.ProjectRequest;
import com.securityhub.project.dto.ProjectResponse;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.error.ConflictException;
import com.securityhub.shared.error.NotFoundException;
import com.securityhub.shared.web.PageableSupport;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
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
class ProjectServiceTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long OTHER_COMPANY_ID = 2L;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditService auditService;

    private ProjectService projectService;
    private Company company;
    private User admin;
    private AuthenticatedUser current;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(projectRepository, companyRepository, userRepository, auditService);
        company = new Company("Acme", "acme");
        ReflectionTestUtils.setField(company, "id", COMPANY_ID);
        admin = new User(company, "Administradora", "admin@acme.test", "hash", Role.ADMIN);
        ReflectionTestUtils.setField(admin, "id", 10L);
        current = AuthenticatedUser.from(admin);
    }

    // --- create -------------------------------------------------------------

    @Test
    void createPersistsProjectScopedToTheAuthenticatedCompany() {
        stubCreateCollaborators();
        when(projectRepository.existsByCompanyIdAndNameIgnoreCase(COMPANY_ID, "Portal")).thenReturn(false);

        ProjectResponse response = projectService.create(current, request("  Portal  ", "  Portal público  ", null));

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        Project saved = captor.getValue();

        assertThat(saved.getCompany().getId()).isEqualTo(COMPANY_ID);
        assertThat(saved.getName()).isEqualTo("Portal");
        assertThat(saved.getDescription()).isEqualTo("Portal público");
        assertThat(saved.getCreatedBy()).isSameAs(admin);
        assertThat(saved.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(response.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(response.getCreatedByName()).isEqualTo("Administradora");
        assertThat(response.getAssetCount()).isZero();
    }

    @Test
    void createHonoursAnExplicitStatus() {
        stubCreateCollaborators();
        when(projectRepository.existsByCompanyIdAndNameIgnoreCase(anyLong(), anyString())).thenReturn(false);

        ProjectResponse response = projectService.create(current,
                request("Legado", null, ProjectStatus.ARCHIVED));

        assertThat(response.getStatus()).isEqualTo(ProjectStatus.ARCHIVED);
        assertThat(response.getDescription()).isNull();
    }

    @Test
    void createRejectsDuplicatedNameInTheSameCompany() {
        when(projectRepository.existsByCompanyIdAndNameIgnoreCase(COMPANY_ID, "Portal")).thenReturn(true);

        assertThatThrownBy(() -> projectService.create(current, request("Portal", null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("nome");

        verify(projectRepository, never()).save(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void createRecordsAuditUnderTheProjectEntityType() {
        stubCreateCollaborators();
        when(projectRepository.existsByCompanyIdAndNameIgnoreCase(anyLong(), anyString())).thenReturn(false);

        projectService.create(current, request("Portal", "Descrição", null));

        AuditEntry entry = captureAudit();
        assertThat(entry.getEntityType()).isEqualTo("Project");
        assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(entry.getCompanyId()).isEqualTo(COMPANY_ID);
        assertThat(entry.getActorId()).isEqualTo(admin.getId());
        assertThat(entry.getEntityId()).isEqualTo(99L);
        assertThat(entry.getOldValue()).isNull();
        assertThat(entry.getNewValue())
                .containsEntry("name", "Portal")
                .containsEntry("description", "Descrição")
                .containsEntry("status", "ACTIVE");
    }

    // --- get / tenant isolation ---------------------------------------------

    @Test
    void getReturnsNotFoundWhenTheProjectBelongsToAnotherCompany() {
        when(projectRepository.findByIdAndCompanyId(7L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.get(current, 7L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Projeto 7");
    }

    @Test
    void getAlwaysQueriesWithTheCompanyOfThePrincipal() {
        AuthenticatedUser intruder = new AuthenticatedUser(50L, OTHER_COMPANY_ID, "x@other.test",
                "Intrusa", Role.ADMIN, true);
        when(projectRepository.findByIdAndCompanyId(7L, OTHER_COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.get(intruder, 7L)).isInstanceOf(NotFoundException.class);

        verify(projectRepository).findByIdAndCompanyId(7L, OTHER_COMPANY_ID);
        verify(projectRepository, never()).findById(any());
    }

    @Test
    void updateReturnsNotFoundForAnUnknownProject() {
        when(projectRepository.findByIdAndCompanyId(7L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.update(current, 7L, request("Novo", null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteReturnsNotFoundForAnUnknownProject() {
        when(projectRepository.findByIdAndCompanyId(7L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.delete(current, 7L)).isInstanceOf(NotFoundException.class);

        verify(projectRepository, never()).delete(any());
    }

    // --- update / delete ----------------------------------------------------

    @Test
    void updateChangesFieldsAndAuditsBeforeAndAfter() {
        Project project = existingProject("Portal", "Antiga", ProjectStatus.ACTIVE);
        when(projectRepository.findByIdAndCompanyId(99L, COMPANY_ID)).thenReturn(Optional.of(project));
        when(projectRepository.existsByCompanyIdAndNameIgnoreCaseAndIdNot(COMPANY_ID, "Portal Novo", 99L))
                .thenReturn(false);

        ProjectResponse response = projectService.update(current, 99L,
                request("Portal Novo", "Nova", ProjectStatus.ARCHIVED));

        assertThat(response.getName()).isEqualTo("Portal Novo");
        assertThat(response.getStatus()).isEqualTo(ProjectStatus.ARCHIVED);

        AuditEntry entry = captureAudit();
        assertThat(entry.getEntityType()).isEqualTo("Project");
        assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
        assertThat(entry.getOldValue())
                .containsEntry("name", "Portal")
                .containsEntry("description", "Antiga")
                .containsEntry("status", "ACTIVE");
        assertThat(entry.getNewValue())
                .containsEntry("name", "Portal Novo")
                .containsEntry("description", "Nova")
                .containsEntry("status", "ARCHIVED");
    }

    @Test
    void updateKeepsTheCurrentStatusWhenTheRequestOmitsIt() {
        Project project = existingProject("Portal", null, ProjectStatus.ARCHIVED);
        when(projectRepository.findByIdAndCompanyId(99L, COMPANY_ID)).thenReturn(Optional.of(project));
        when(projectRepository.existsByCompanyIdAndNameIgnoreCaseAndIdNot(anyLong(), anyString(), anyLong()))
                .thenReturn(false);

        assertThat(projectService.update(current, 99L, request("Portal", null, null)).getStatus())
                .isEqualTo(ProjectStatus.ARCHIVED);
    }

    @Test
    void updateRejectsARenameOntoAnotherProjectName() {
        Project project = existingProject("Portal", null, ProjectStatus.ACTIVE);
        when(projectRepository.findByIdAndCompanyId(99L, COMPANY_ID)).thenReturn(Optional.of(project));
        when(projectRepository.existsByCompanyIdAndNameIgnoreCaseAndIdNot(COMPANY_ID, "Outro", 99L))
                .thenReturn(true);

        assertThatThrownBy(() -> projectService.update(current, 99L, request("Outro", null, null)))
                .isInstanceOf(ConflictException.class);

        verify(projectRepository, never()).save(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void deleteRemovesTheProjectAndAuditsTheRemovedValues() {
        Project project = existingProject("Portal", "Antiga", ProjectStatus.ACTIVE);
        when(projectRepository.findByIdAndCompanyId(99L, COMPANY_ID)).thenReturn(Optional.of(project));

        projectService.delete(current, 99L);

        verify(projectRepository).delete(project);

        AuditEntry entry = captureAudit();
        assertThat(entry.getEntityType()).isEqualTo("Project");
        assertThat(entry.getAction()).isEqualTo(AuditAction.DELETE);
        assertThat(entry.getEntityId()).isEqualTo(99L);
        assertThat(entry.getNewValue()).isNull();
        assertThat(entry.getOldValue()).containsEntry("name", "Portal");
    }

    // --- search -------------------------------------------------------------

    @Test
    void searchDropsASortPropertyThatIsNotWhitelisted() {
        stubEmptySearch();

        projectService.search(current, null, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "passwordHash")));

        assertThat(capturePageable().getSort()).isEqualTo(ProjectService.DEFAULT_SORT);
    }

    @Test
    void searchKeepsAWhitelistedSortProperty() {
        stubEmptySearch();

        projectService.search(current, null, null,
                PageRequest.of(2, 15, Sort.by(Sort.Direction.ASC, "name")));

        Pageable used = capturePageable();
        assertThat(used.getPageNumber()).isEqualTo(2);
        assertThat(used.getPageSize()).isEqualTo(15);
        assertThat(used.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "name"));
    }

    @Test
    void searchCapsThePageSize() {
        stubEmptySearch();

        projectService.search(current, null, null, PageRequest.of(0, 5000));

        assertThat(capturePageable().getPageSize()).isEqualTo(PageableSupport.MAX_PAGE_SIZE);
    }

    @Test
    void searchAlwaysBuildsATenantScopedSpecification() {
        stubEmptySearch();

        projectService.search(current, "  PorTAL  ", ProjectStatus.ACTIVE, PageRequest.of(0, 20));

        ArgumentCaptor<Specification<Project>> captor = specificationCaptor();
        verify(projectRepository).findAll(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isNotNull();
    }

    // --- helpers ------------------------------------------------------------

    private void stubCreateCollaborators() {
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(company));
        when(userRepository.findByIdAndCompanyId(admin.getId(), COMPANY_ID)).thenReturn(Optional.of(admin));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            ReflectionTestUtils.setField(project, "id", 99L);
            return project;
        });
    }

    private void stubEmptySearch() {
        when(projectRepository.findAll(ArgumentMatchers.<Specification<Project>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(projectRepository).findAll(ArgumentMatchers.<Specification<Project>>any(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Specification<Project>> specificationCaptor() {
        return (ArgumentCaptor<Specification<Project>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(Specification.class);
    }

    private AuditEntry captureAudit() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(captor.capture());
        return captor.getValue();
    }

    private Project existingProject(String name, String description, ProjectStatus status) {
        Project project = new Project(company, name, description, status, admin);
        ReflectionTestUtils.setField(project, "id", 99L);
        return project;
    }

    private ProjectRequest request(String name, String description, ProjectStatus status) {
        ProjectRequest request = new ProjectRequest();
        request.setName(name);
        request.setDescription(description);
        request.setStatus(status);
        return request;
    }
}
