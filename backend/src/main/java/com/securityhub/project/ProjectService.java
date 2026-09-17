package com.securityhub.project;

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
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorization lives here, not only on the controller, so a future caller (scheduler,
 * importer, another service) cannot bypass the role matrix of docs/permissions.md. Every read is
 * scoped by the company of the authenticated principal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    static final String ENTITY_TYPE = "Project";

    /**
     * Whitelist for the client-supplied {@code sort}. Anything outside it is dropped by
     * PageableSupport instead of reaching Spring Data as an arbitrary property path.
     */
    static final Set<String> SORTABLE_PROPERTIES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("name", "status", "createdAt", "updatedAt")));

    static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ProjectRepository projectRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<ProjectResponse> search(AuthenticatedUser current, String search, ProjectStatus status,
                                        Pageable pageable) {
        Pageable sanitized = PageableSupport.sanitize(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT);
        Page<Project> page = projectRepository
                .findAll(ProjectSpecifications.filter(current.getCompanyId(), search, status), sanitized);
        Map<Long, Long> assetCounts = assetCounts(current.getCompanyId(), page.getContent());
        return page.map(project -> ProjectMapper.toResponse(project,
                assetCounts.getOrDefault(project.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(AuthenticatedUser current, Long id) {
        Project project = require(current, id);
        return ProjectMapper.toResponse(project, projectRepository.countAssetsByProjectId(id));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProjectResponse create(AuthenticatedUser current, ProjectRequest request) {
        String name = ProjectMapper.normalizeName(request.getName());
        if (projectRepository.existsByCompanyIdAndNameIgnoreCase(current.getCompanyId(), name)) {
            throw new ConflictException("Já existe um projeto com esse nome nesta empresa");
        }

        Company company = companyRepository.findById(current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Empresa", current.getCompanyId()));
        User author = userRepository.findByIdAndCompanyId(current.getId(), current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Usuário", current.getId()));

        Project project = new Project(company, name, ProjectMapper.normalizeDescription(request.getDescription()),
                request.getStatus(), author);
        projectRepository.save(project);

        auditService.record(AuditEntry.created(current, ENTITY_TYPE, project.getId(), snapshot(project)));
        log.info("Projeto {} criado na empresa {}", project.getId(), current.getCompanyId());
        // A brand new project cannot have assets yet, so the count is known without a query.
        return ProjectMapper.toResponse(project, 0L);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProjectResponse update(AuthenticatedUser current, Long id, ProjectRequest request) {
        Project project = require(current, id);
        String name = ProjectMapper.normalizeName(request.getName());
        if (projectRepository.existsByCompanyIdAndNameIgnoreCaseAndIdNot(current.getCompanyId(), name, id)) {
            throw new ConflictException("Já existe um projeto com esse nome nesta empresa");
        }

        Map<String, Object> before = snapshot(project);
        ProjectMapper.applyUpdate(project, request);
        projectRepository.save(project);

        auditService.record(AuditEntry.updated(current, ENTITY_TYPE, project.getId(), before, snapshot(project)));
        return ProjectMapper.toResponse(project, projectRepository.countAssetsByProjectId(id));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(AuthenticatedUser current, Long id) {
        Project project = require(current, id);
        ensureNoChildren(project);

        Map<String, Object> before = snapshot(project);
        projectRepository.delete(project);

        auditService.record(AuditEntry.deleted(current, ENTITY_TYPE, project.getId(), before));
        log.info("Projeto {} excluído na empresa {}", id, current.getCompanyId());
    }

    /**
     * Single place where the "não apagar filhos em cascata silenciosamente" rule of
     * docs/permissions.md is enforced. The foreign key has no ON DELETE CASCADE, so the
     * alternative would be a raw integrity violation instead of a readable conflict.
     */
    private void ensureNoChildren(Project project) {
        long assets = projectRepository.countAssetsByProjectId(project.getId());
        if (assets > 0) {
            throw new ConflictException(
                    "O projeto possui " + assets + " ativo(s) e não pode ser excluído");
        }
    }

    /**
     * A project of another company must be indistinguishable from one that does not
     * exist, so this never throws 403.
     */
    private Project require(AuthenticatedUser current, Long id) {
        return projectRepository.findByIdAndCompanyId(id, current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Projeto", id));
    }

    /**
     * One grouped query for the whole page: mapping the assets as a collection would make
     * every listed project initialize it, and counting per row would be an N+1.
     */
    private Map<Long, Long> assetCounts(Long companyId, List<Project> projects) {
        if (projects.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = new ArrayList<>(projects.size());
        for (Project project : projects) {
            ids.add(project.getId());
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : projectRepository.countAssetsByProject(companyId, ids)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    private Map<String, Object> snapshot(Project project) {
        Map<String, Object> values = AuditEntry.values();
        values.put("name", project.getName());
        values.put("description", project.getDescription());
        values.put("status", project.getStatus() == null ? null : project.getStatus().name());
        return values;
    }
}
