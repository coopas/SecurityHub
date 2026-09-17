package com.securityhub.asset;

import com.securityhub.asset.dto.AssetRequest;
import com.securityhub.asset.dto.AssetResponse;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.project.Project;
import com.securityhub.project.ProjectRepository;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.error.ConflictException;
import com.securityhub.shared.error.NotFoundException;
import com.securityhub.shared.web.PageableSupport;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
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
 * scoped by the company of the authenticated principal, and every write also proves that
 * the referenced project belongs to that same company.
 *
 * Entities are mapped to DTOs inside the transactional methods: {@code open-in-view} is
 * disabled, so a lazy {@code project} touched by the controller would fail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    static final String ENTITY_TYPE = "Asset";

    /**
     * Whitelist for the client-supplied {@code sort}. Anything outside it is dropped by
     * PageableSupport instead of reaching Spring Data as an arbitrary property path.
     */
    static final Set<String> SORTABLE_PROPERTIES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("name", "type", "environment", "criticality", "createdAt", "updatedAt")));

    static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final AssetRepository assetRepository;
    private final ProjectRepository projectRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<AssetResponse> search(AuthenticatedUser current, String search, Long projectId, AssetType type,
                                      Environment environment, Criticality criticality, Pageable pageable) {
        Pageable sanitized = PageableSupport.sanitize(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT);
        return assetRepository
                .findAll(AssetSpecifications.filter(current.getCompanyId(), search, projectId, type,
                        environment, criticality), sanitized)
                .map(AssetMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public AssetResponse get(AuthenticatedUser current, Long id) {
        return AssetMapper.toResponse(require(current, id));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public AssetResponse create(AuthenticatedUser current, AssetRequest request) {
        Project project = requireProject(current, request.getProjectId());
        String identifier = AssetMapper.normalizeIdentifier(request.getIdentifier());
        ensureIdentifierIsFree(project.getId(), identifier, null);

        // The asset's company is by definition the project's company; reusing the reference
        // keeps the denormalized column consistent and avoids a second lookup.
        Asset asset = new Asset(project.getCompany(), project, AssetMapper.normalizeName(request.getName()),
                AssetMapper.normalizeDescription(request.getDescription()), request.getType(), identifier,
                request.getEnvironment(), request.getCriticality());
        assetRepository.save(asset);

        auditService.record(AuditEntry.created(current, ENTITY_TYPE, asset.getId(), snapshot(asset)));
        log.info("Ativo {} criado no projeto {} da empresa {}", asset.getId(), project.getId(),
                current.getCompanyId());
        return AssetMapper.toResponse(asset);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public AssetResponse update(AuthenticatedUser current, Long id, AssetRequest request) {
        Asset asset = require(current, id);
        Map<String, Object> before = snapshot(asset);

        // Moving the asset is allowed only to another project of the same company, and the
        // uniqueness check then runs against the target project, not the current one.
        Project project = asset.getProject();
        if (request.getProjectId() != null && !request.getProjectId().equals(project.getId())) {
            project = requireProject(current, request.getProjectId());
        }
        ensureIdentifierIsFree(project.getId(), AssetMapper.normalizeIdentifier(request.getIdentifier()), id);

        asset.setProject(project);
        AssetMapper.applyUpdate(asset, request);
        assetRepository.save(asset);

        auditService.record(AuditEntry.updated(current, ENTITY_TYPE, asset.getId(), before, snapshot(asset)));
        return AssetMapper.toResponse(asset);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(AuthenticatedUser current, Long id) {
        Asset asset = require(current, id);
        ensureNoChildren(asset);

        Map<String, Object> before = snapshot(asset);
        assetRepository.delete(asset);

        auditService.record(AuditEntry.deleted(current, ENTITY_TYPE, asset.getId(), before));
        log.info("Ativo {} excluído na empresa {}", id, current.getCompanyId());
    }

    /**
     * Single place where the "não apagar filhos em cascata silenciosamente" rule of
     * docs/permissions.md will be enforced for assets. The vulnerabilities table does not exist yet,
     * so there is nothing to count; Fase 5 fills this in with a ConflictException when the
     * asset still has vulnerabilities. Kept as a named step so the rule is impossible to
     * miss, exactly like ProjectService.ensureNoChildren does for assets.
     */
    private void ensureNoChildren(Asset asset) {
        // Fase 5: reject the deletion with ConflictException when the vulnerabilities
        // repository reports rows for this asset.
    }

    /**
     * The identifier is optional, so a null one never conflicts: several assets of the same
     * project may have none. When it is filled the comparison is case-insensitive, matching
     * the partial unique index of V4__assets.sql.
     *
     * @param currentId id of the asset being updated, or {@code null} on creation
     */
    private void ensureIdentifierIsFree(Long projectId, String identifier, Long currentId) {
        if (identifier == null) {
            return;
        }
        boolean taken = currentId == null
                ? assetRepository.existsByProjectIdAndIdentifierIgnoreCase(projectId, identifier)
                : assetRepository.existsByProjectIdAndIdentifierIgnoreCaseAndIdNot(projectId, identifier, currentId);
        if (taken) {
            throw new ConflictException("Já existe um ativo com esse identificador neste projeto");
        }
    }

    /**
     * An asset of another company must be indistinguishable from one that does not exist,
     * so this never throws 403.
     */
    private Asset require(AuthenticatedUser current, Long id) {
        return assetRepository.findByIdAndCompanyId(id, current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Ativo", id));
    }

    /**
     * a project of another company is reported as not found instead of forbidden,
     * otherwise the response would confirm that the id exists somewhere else.
     */
    private Project requireProject(AuthenticatedUser current, Long projectId) {
        return projectRepository.findByIdAndCompanyId(projectId, current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Projeto", projectId));
    }

    private Map<String, Object> snapshot(Asset asset) {
        Map<String, Object> values = AuditEntry.values();
        values.put("name", asset.getName());
        values.put("description", asset.getDescription());
        values.put("type", asset.getType() == null ? null : asset.getType().name());
        values.put("identifier", asset.getIdentifier());
        values.put("environment", asset.getEnvironment() == null ? null : asset.getEnvironment().name());
        values.put("criticality", asset.getCriticality() == null ? null : asset.getCriticality().name());
        values.put("projectId", asset.getProject() == null ? null : asset.getProject().getId());
        return values;
    }
}
