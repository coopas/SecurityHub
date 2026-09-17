package com.securityhub.scan;

import com.securityhub.asset.Asset;
import com.securityhub.asset.AssetMapper;
import com.securityhub.asset.AssetRepository;
import com.securityhub.attachment.AttachmentFilenameSanitizer;
import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.project.Project;
import com.securityhub.project.ProjectRepository;
import com.securityhub.scan.dto.ScanFindingResponse;
import com.securityhub.scan.dto.ScanImportResponse;
import com.securityhub.scan.dto.ScanImportSummaryResponse;
import com.securityhub.scan.parser.ScannerParser;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.error.BadRequestException;
import com.securityhub.shared.error.ConflictException;
import com.securityhub.shared.error.NotFoundException;
import com.securityhub.shared.error.PayloadTooLargeException;
import com.securityhub.shared.web.PageableSupport;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import com.securityhub.vulnerability.Vulnerability;
import com.securityhub.vulnerability.VulnerabilityMapper;
import com.securityhub.vulnerability.VulnerabilityRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Turns a scanner report into vulnerabilities, with a human decision in the middle.
 *
 * <p>The lifecycle is upload, preview, then confirm or discard. Nothing is created by the
 * upload: it parses the report, resolves each finding's target against the assets of the chosen
 * project, marks the findings this company already has, and writes all of that to
 * {@code scan_findings} as a proposal. Only {@link #confirm} creates anything, and only for the
 * findings that are still MATCHED by then.
 *
 * <p>Three decisions shape everything below and are worth stating once.
 *
 * <ul>
 * <li><b>An asset is never created.</b> A scanner reports a host, this product knows assets by
 * an identifier a person typed, and inventing an asset from a hostname would fill the inventory
 * with rows nobody owns. A finding whose target does not resolve stays UNMATCHED and waits for
 * somebody to say what it is.</li>
 * <li><b>A duplicate is skipped and counted, never merged.</b> Re-importing a report must not
 * undo human work: a status somebody moved, an assignee somebody chose, a comment somebody
 * wrote. Updating or reopening the existing vulnerability would do exactly that, silently, for
 * every finding of the report at once.</li>
 * <li><b>The import is synchronous.</b> There is no job store, no status endpoint and no
 * {@code @Async}, because there is a hard limit instead: {@code securityhub.scan.max-findings}
 * turns the unbounded case into a refusal the uploader can act on. That also keeps
 * {@code @PreAuthorize} honest — it evaluates against the {@code SecurityContextHolder}, which
 * is a thread local, and nothing here hops threads.</li>
 * </ul>
 *
 * <p>Authorization lives on this class and not on the controller, like everywhere else in the
 * project, so the role matrix also applies to callers that never go through HTTP. Every
 * mutating method is ADMIN or ANALYST: whoever may create a vulnerability by hand may import
 * one. Reads are open to any member of the company, like the vulnerabilities themselves.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanImportService {

    static final String ENTITY_TYPE = "ScanImport";

    /** Mirrors {@code scan_findings.rule_id VARCHAR(200)}. */
    static final int MAX_RULE_ID = 200;

    /** Mirrors {@code scan_findings.target VARCHAR(2000)}. */
    static final int MAX_TARGET = 2000;

    /**
     * Whitelist for the client-supplied {@code sort}. Anything outside it is dropped by
     * PageableSupport instead of reaching Spring Data as an arbitrary property path.
     */
    static final Set<String> SORTABLE_PROPERTIES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("createdAt", "updatedAt", "status", "format", "sizeBytes", "totalFindings")));

    static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ScanImportRepository scanImportRepository;
    private final ScanFindingRepository scanFindingRepository;
    private final ProjectRepository projectRepository;
    private final AssetRepository assetRepository;
    private final UserRepository userRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final ScanFileStorage scanFileStorage;
    private final ScanProperties scanProperties;
    private final AuditService auditService;

    /**
     * Every parser the context holds. There is no registry and no factory on purpose: adding a
     * scanner is adding a {@code @Component} that answers a new {@link ScanFormat}, and this
     * list picks it up without a second place to remember.
     */
    private final List<ScannerParser> parsers;

    /**
     * The order of the checks below is the design, not housekeeping, and it is the order
     * {@code AttachmentService.upload} established: everything that can refuse the request
     * happens before a single byte reaches the disk, and the write itself is the last thing
     * before the rows. Read it top to bottom:
     *
     * <ol>
     * <li>project — a project of another company is 404 and never 403, so nothing after it can
     * confirm that the id exists somewhere;</li>
     * <li>empty — 400;</li>
     * <li>size — 413, from the application limit, which may be below the container's;</li>
     * <li>parse — 400 from {@code ScanParseException} when the report cannot be read at all;</li>
     * <li>count — 400 with a message that says what to do, and <em>before</em> anything is
     * written: a report over the limit must leave no row and no file behind;</li>
     * <li>match and deduplicate, which are reads;</li>
     * <li>write the file, then save the rows.</li>
     * </ol>
     *
     * <p>That last order looks backwards and is deliberate, for the reason
     * {@code AttachmentService} documents at length: the two failure modes are not symmetric. A
     * row whose file is missing is a visible, broken record — it is in the history and nothing
     * can back it up. A file whose row is missing is invisible; nothing references it and it is
     * found when the volume fills. So the file is written first, leaving only the harmless
     * direction possible, and an {@code afterCompletion} hook removes it whenever the
     * transaction ends any way other than a commit.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ScanImportResponse upload(AuthenticatedUser current, Long projectId, ScanFormat format,
                                     MultipartFile file) {
        Project project = requireProject(current, projectId);

        if (file == null || file.isEmpty()) {
            throw new BadRequestException("O relatório enviado está vazio");
        }
        if (file.getSize() > scanProperties.getMaxUploadBytes()) {
            throw tooLarge();
        }

        byte[] content = read(file);
        if (content.length == 0) {
            throw new BadRequestException("O relatório enviado está vazio");
        }
        // Re-checked against the bytes actually read: getSize reports what the part declared.
        if (content.length > scanProperties.getMaxUploadBytes()) {
            throw tooLarge();
        }

        List<ScanFinding> parsed = parserFor(format).parse(new ByteArrayInputStream(content));
        if (parsed.size() > scanProperties.getMaxFindings()) {
            throw new BadRequestException("O relatório contém " + parsed.size()
                    + " achados e o limite por importação é " + scanProperties.getMaxFindings()
                    + "; filtre o relatório no scanner (por severidade ou por host) ou divida-o em"
                    + " arquivos menores e envie um de cada vez");
        }

        String originalFilename = AttachmentFilenameSanitizer.sanitize(file.getOriginalFilename());
        User importer = requireImporter(current);
        String storedFilename = UUID.randomUUID().toString().replace("-", "");

        scanFileStorage.store(storedFilename, content);
        deleteFileUnlessCommitted(storedFilename);

        ScanImport scanImport = new ScanImport(project.getCompany(), project, format, originalFilename,
                storedFilename, content.length, importer);
        scanImportRepository.save(scanImport);

        List<ScanImportFinding> findings = stage(current, project, scanImport, parsed);
        scanFindingRepository.saveAll(findings);
        recount(scanImport, findings);
        scanImportRepository.save(scanImport);

        log.info("Importação {} criada no projeto {} da empresa {}: {} achado(s), {} com ativo, "
                        + "{} sem ativo, {} duplicado(s)", scanImport.getId(), projectId,
                current.getCompanyId(), scanImport.getTotalFindings(), scanImport.getMatchedCount(),
                scanImport.getUnmatchedCount(), scanImport.getDuplicateCount());
        return ScanImportMapper.toResponse(scanImport, findings);
    }

    /** The staged import with every finding, which is what the preview screen renders. */
    @Transactional(readOnly = true)
    public ScanImportResponse preview(AuthenticatedUser current, Long importId) {
        ScanImport scanImport = require(current, importId);
        return ScanImportMapper.toResponse(scanImport, findingsOf(current, importId));
    }

    /**
     * Gives an UNMATCHED finding the asset its target did not resolve to.
     *
     * <p>The asset only has to belong to the caller's company, not to the project of the
     * import. A scanner that reported {@code 10.0.0.11} may well have hit an asset registered
     * under another project of the same tenant, and refusing the mapping would leave the
     * operator with a finding they can see, know the owner of, and cannot import.
     *
     * <p>Only an UNMATCHED finding may be mapped. A MATCHED one already has its asset, and a
     * DUPLICATE one is not going to be imported whatever asset it points at — allowing either
     * would be offering a change with no effect.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ScanFindingResponse mapFinding(AuthenticatedUser current, Long importId, Long findingId,
                                          Long assetId) {
        ScanImport scanImport = require(current, importId);
        requirePending(scanImport, "mapear achados de");

        ScanImportFinding finding = scanFindingRepository
                .findByIdAndCompanyIdAndScanImportId(findingId, current.getCompanyId(), importId)
                .orElseThrow(() -> NotFoundException.of("Achado", findingId));
        if (finding.getStatus() != ScanFindingStatus.UNMATCHED) {
            throw new ConflictException("Somente um achado sem ativo pode ser mapeado; este está "
                    + finding.getStatus());
        }

        finding.setAsset(requireAsset(current, assetId));
        finding.setStatus(ScanFindingStatus.MATCHED);
        scanFindingRepository.save(finding);

        recount(scanImport, findingsOf(current, importId));
        scanImportRepository.save(scanImport);

        log.info("Achado {} da importação {} mapeado no ativo {} da empresa {}", findingId, importId,
                assetId, current.getCompanyId());
        return ScanImportMapper.toFindingResponse(finding);
    }

    /**
     * Creates one vulnerability per MATCHED finding, in a single batch, and closes the import.
     *
     * <p>{@code VulnerabilityService.create} is deliberately not called per finding. It is the
     * right entry point for a person filling a form — it resolves an assignee, proves the asset
     * is theirs and writes a CREATE audit row — and every one of those is wrong here. The asset
     * was already proved at upload, there is no assignee, and the audit row is the problem: one
     * CREATE per finding would bury the trail of a company under hundreds of rows saying the
     * same thing, and the per-finding traceability those rows would carry already exists, in a
     * better place, as the {@code vulnerability_id} of {@code scan_findings}. So the entities
     * are built here with the same constructor and the same {@link VulnerabilityMapper}
     * normalizers, saved in one batch, and the trail gets a single {@code SCAN_IMPORT} row
     * carrying the summary.
     *
     * <p>Everything that is not MATCHED becomes SKIPPED: the UNMATCHED findings nobody mapped
     * and the DUPLICATE ones this company already has. A duplicate is skipped and counted and
     * never merged into the vulnerability it duplicates — that row may have an owner, a status
     * somebody moved and a discussion under it, and a re-import must not touch any of it.
     *
     * <p>A second confirm is a 409 and not an idempotent success: the first one created rows,
     * and answering 200 to the second would tell a client that retried it that it created rows
     * too.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ScanImportResponse confirm(AuthenticatedUser current, Long importId) {
        ScanImport scanImport = require(current, importId);
        requirePending(scanImport, "confirmar");

        List<ScanImportFinding> findings = findingsOf(current, importId);
        User author = requireImporter(current);

        // Asked again here, and not reused from the upload: DUPLICATE was decided against the
        // backlog as it stood when the file arrived, and another import may have been confirmed
        // since. Seeding the set with what already exists and letting `add` reject a repeat also
        // covers two MATCHED rows of this same import landing on one fingerprint.
        Set<String> taken = knownFingerprints(current, matchedFingerprints(findings));

        List<ScanImportFinding> importable = new ArrayList<>();
        List<Vulnerability> created = new ArrayList<>();
        for (ScanImportFinding finding : findings) {
            if (finding.getStatus() != ScanFindingStatus.MATCHED || finding.getAsset() == null
                    || !taken.add(finding.getFingerprint())) {
                finding.setStatus(ScanFindingStatus.SKIPPED);
                continue;
            }
            Vulnerability vulnerability = new Vulnerability(scanImport.getCompany(), finding.getAsset(),
                    VulnerabilityMapper.normalizeTitle(finding.getTitle()),
                    VulnerabilityMapper.normalizeDescription(finding.getDescription()),
                    finding.getSeverity(), finding.getCvssScore(),
                    VulnerabilityMapper.normalizeCve(finding.getCve()),
                    finding.getDiscoveredAt(), null, null, author);
            // The fingerprint is what makes the next import of the same report a no-op, and the
            // partial unique index of V9 is what makes that a guarantee rather than a promise.
            vulnerability.setFingerprint(finding.getFingerprint());
            created.add(vulnerability);
            importable.add(finding);
        }

        vulnerabilityRepository.saveAll(created);
        for (int i = 0; i < created.size(); i++) {
            ScanImportFinding finding = importable.get(i);
            finding.setVulnerability(created.get(i));
            finding.setStatus(ScanFindingStatus.IMPORTED);
        }
        scanFindingRepository.saveAll(findings);

        scanImport.setStatus(ScanImportStatus.CONFIRMED);
        recount(scanImport, findings);
        scanImportRepository.save(scanImport);

        auditService.record(AuditEntry.changed(current, AuditAction.SCAN_IMPORT, ENTITY_TYPE,
                scanImport.getId(), null, summary(scanImport)));
        log.info("Importação {} confirmada na empresa {}: {} vulnerabilidade(s) criada(s), {} ignorado(s)",
                importId, current.getCompanyId(), scanImport.getImportedCount(),
                scanImport.getSkippedCount());
        return ScanImportMapper.toResponse(scanImport, findings);
    }

    /**
     * Throws the proposal away and takes the file with it.
     *
     * <p>The finding rows stay. They cost nothing, and what a report found is a fact worth
     * keeping even when nobody acted on it — the counters of a discarded import are how someone
     * later answers "we did scan that host, and we chose not to import it".
     *
     * <p>Only a PENDING import may be discarded. Discarding a confirmed one would delete the
     * report behind vulnerabilities that exist, which is the one file in this feature that has
     * earned the right to stay.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public void discard(AuthenticatedUser current, Long importId) {
        ScanImport scanImport = require(current, importId);
        requirePending(scanImport, "descartar");

        scanImport.setStatus(ScanImportStatus.DISCARDED);
        scanImportRepository.save(scanImport);

        deleteFileAfterCommit(scanImport.getStoredFilename());
        log.info("Importação {} descartada na empresa {}", importId, current.getCompanyId());
    }

    @Transactional(readOnly = true)
    public Page<ScanImportSummaryResponse> history(AuthenticatedUser current, Pageable pageable) {
        Pageable sanitized = PageableSupport.sanitize(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT);
        return scanImportRepository.findByCompanyId(current.getCompanyId(), sanitized)
                .map(ScanImportMapper::toSummary);
    }

    // --- staging ------------------------------------------------------------

    /**
     * Turns the parsed findings into rows, deciding a status for each.
     *
     * <p>A target is normalized with {@link AssetMapper#normalizeIdentifier} — trim, blank
     * becomes null — before it is looked up, because the unique index of V4 is
     * {@code lower(identifier)} and does <em>not</em> trim: without this, {@code "host "} reads
     * as a different asset from {@code host} and the finding would be reported as unmatched
     * against an asset that is right there.
     *
     * <p>The lookup is cached per distinct target for the duration of the report. A ZAP report
     * routinely carries forty instances of the same site; one query per distinct target instead
     * of one per finding is the difference between a query and a scan.
     */
    private List<ScanImportFinding> stage(AuthenticatedUser current, Project project,
                                          ScanImport scanImport, List<ScanFinding> parsed) {
        Set<String> known = existingFingerprints(current, parsed);
        Map<String, Optional<Asset>> byTarget = new HashMap<>();
        Set<String> seen = new HashSet<>();

        List<ScanImportFinding> staged = new ArrayList<>(parsed.size());
        for (ScanFinding finding : parsed) {
            String target = AssetMapper.normalizeIdentifier(finding.getTarget());
            Asset asset = target == null ? null
                    : byTarget.computeIfAbsent(target, key -> assetRepository
                            .findByProjectIdAndIdentifierIgnoreCase(project.getId(), key)).orElse(null);

            staged.add(new ScanImportFinding(scanImport.getCompany(), scanImport,
                    truncate(finding.getRuleId(), MAX_RULE_ID), finding.getTitle(),
                    finding.getDescription(), finding.getSeverity(), finding.getCvssScore(),
                    finding.getCve(), truncate(finding.getTarget(), MAX_TARGET),
                    finding.getDiscoveredAt(), finding.getFingerprint(), asset,
                    status(finding, asset, known, seen)));
        }
        return staged;
    }

    /**
     * DUPLICATE wins over MATCHED, and the two sources of duplication are treated alike: a
     * fingerprint this company already carries on a vulnerability, and a fingerprint that
     * appeared earlier in this same report. The second is not hypothetical — a scanner can
     * report the same rule against the same target twice — and without it the confirm would
     * die on the unique index of V9 after having created half the batch.
     */
    private ScanFindingStatus status(ScanFinding finding, Asset asset, Set<String> known,
                                     Set<String> seen) {
        boolean repeated = !seen.add(finding.getFingerprint());
        if (repeated || known.contains(finding.getFingerprint())) {
            return ScanFindingStatus.DUPLICATE;
        }
        return asset == null ? ScanFindingStatus.UNMATCHED : ScanFindingStatus.MATCHED;
    }

    private Set<String> existingFingerprints(AuthenticatedUser current, List<ScanFinding> parsed) {
        Set<String> fingerprints = new HashSet<>();
        for (ScanFinding finding : parsed) {
            if (finding.getFingerprint() != null) {
                fingerprints.add(finding.getFingerprint());
            }
        }
        return knownFingerprints(current, fingerprints);
    }

    private Set<String> matchedFingerprints(List<ScanImportFinding> findings) {
        Set<String> fingerprints = new HashSet<>();
        for (ScanImportFinding finding : findings) {
            if (finding.getStatus() == ScanFindingStatus.MATCHED && finding.getFingerprint() != null) {
                fingerprints.add(finding.getFingerprint());
            }
        }
        return fingerprints;
    }

    /** Always mutable: the confirm keeps adding to it as it decides each finding. */
    private Set<String> knownFingerprints(AuthenticatedUser current, Set<String> candidates) {
        if (candidates.isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(
                scanImportRepository.findExistingFingerprints(current.getCompanyId(), candidates));
    }

    /**
     * Recomputed from the rows rather than incremented, so the six counters cannot drift from
     * what {@code scan_findings} actually says. It runs in the same transaction that changed a
     * status, which is what makes the cache and the rows commit together or not at all.
     */
    private void recount(ScanImport scanImport, List<ScanImportFinding> findings) {
        int matched = 0;
        int unmatched = 0;
        int duplicate = 0;
        int imported = 0;
        int skipped = 0;
        for (ScanImportFinding finding : findings) {
            switch (finding.getStatus()) {
                case MATCHED:
                    matched++;
                    break;
                case UNMATCHED:
                    unmatched++;
                    break;
                case DUPLICATE:
                    duplicate++;
                    break;
                case IMPORTED:
                    imported++;
                    break;
                default:
                    skipped++;
                    break;
            }
        }
        scanImport.setTotalFindings(findings.size());
        scanImport.setMatchedCount(matched);
        scanImport.setUnmatchedCount(unmatched);
        scanImport.setDuplicateCount(duplicate);
        scanImport.setImportedCount(imported);
        scanImport.setSkippedCount(skipped);
    }

    // --- lookups ------------------------------------------------------------

    /** A project of another company is reported as not found, never as forbidden. */
    private Project requireProject(AuthenticatedUser current, Long projectId) {
        return projectRepository.findByIdAndCompanyId(projectId, current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Projeto", projectId));
    }

    /** Same rule for the import itself: cross-tenant is indistinguishable from nonexistent. */
    private ScanImport require(AuthenticatedUser current, Long importId) {
        return scanImportRepository.findByIdAndCompanyId(importId, current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Importação", importId));
    }

    private Asset requireAsset(AuthenticatedUser current, Long assetId) {
        return assetRepository.findByIdAndCompanyId(assetId, current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Ativo", assetId));
    }

    private User requireImporter(AuthenticatedUser current) {
        return userRepository.findByIdAndCompanyId(current.getId(), current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Usuário", current.getId()));
    }

    private List<ScanImportFinding> findingsOf(AuthenticatedUser current, Long importId) {
        return scanFindingRepository
                .findByCompanyIdAndScanImportIdOrderByIdAsc(current.getCompanyId(), importId);
    }

    /**
     * A closed import is a state conflict and not bad input, so 409 rather than 400 — the same
     * distinction {@code AttachmentService} makes for its attachment cap.
     */
    private void requirePending(ScanImport scanImport, String action) {
        if (!scanImport.getStatus().isPending()) {
            throw new ConflictException("Não é possível " + action + " uma importação com status "
                    + scanImport.getStatus() + "; apenas importações pendentes podem ser alteradas");
        }
    }

    private ScannerParser parserFor(ScanFormat format) {
        for (ScannerParser parser : parsers) {
            if (parser.format() == format) {
                return parser;
            }
        }
        // Unreachable while every ScanFormat has a @Component: the enum is what the controller
        // binds, so an unknown value is already a 400 before it gets here.
        throw new IllegalStateException("Nenhum parser registrado para o formato " + format);
    }

    private byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BadRequestException("Não foi possível ler o relatório enviado");
        }
    }

    private PayloadTooLargeException tooLarge() {
        return new PayloadTooLargeException("O relatório excede o limite de "
                + scanProperties.getMaxUploadBytes() + " bytes");
    }

    /**
     * The parsers already cut every value the vulnerability columns bound. These two are not
     * vulnerability columns — they exist only on the staging row — so the ceiling is enforced
     * here, where it can be a quiet truncation instead of a
     * {@code DataIntegrityViolationException} the global handler would answer with a 409 about
     * a conflict that does not exist. Losing the tail of a query string is smaller than losing
     * the report.
     */
    private static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    // --- files --------------------------------------------------------------

    /** Removes the file the transaction has just written, unless the transaction committed. */
    private void deleteFileUnlessCommitted(String storedFilename) {
        register(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    removeQuietly(storedFilename, "descartado após rollback");
                }
            }
        });
    }

    /**
     * The row says DISCARDED only once the transaction commits, so the file must not disappear
     * before that: a rollback after the delete would leave a PENDING import pointing at nothing.
     */
    private void deleteFileAfterCommit(String storedFilename) {
        register(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                removeQuietly(storedFilename, "removido após commit");
            }
        });
    }

    private void register(TransactionSynchronization synchronization) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(synchronization);
        }
    }

    /**
     * Never rethrows. Both callers run after the transaction has already been decided, so an
     * exception here cannot undo anything — it would only turn a correct answer into a 500
     * while leaving the database exactly as it is. A file left behind is a WARN and a cleanup
     * job's problem.
     */
    private void removeQuietly(String storedFilename, String reason) {
        try {
            scanFileStorage.delete(storedFilename);
        } catch (RuntimeException ex) {
            log.warn("Relatório de scan {} não pôde ser {}", storedFilename, reason, ex);
        }
    }

    /**
     * What the single {@code SCAN_IMPORT} row carries: the six counters, the project, the
     * format and the sanitized filename. Nothing per finding, and nothing from inside the
     * report.
     *
     * <p>{@code AuditService.toJson} truncates at 8000 characters, so a summary that grew with
     * the size of the report would be silently cut in half and stored as broken JSON. These
     * keys are a fixed number of scalars regardless of whether the report had four findings or
     * four hundred. The per-finding detail that does not fit here is not lost: it is in
     * {@code scan_findings}, which is queryable, joinable and scoped to the import.
     */
    private Map<String, Object> summary(ScanImport scanImport) {
        Map<String, Object> values = AuditEntry.values();
        values.put("projectId", scanImport.getProject() == null ? null : scanImport.getProject().getId());
        values.put("format", scanImport.getFormat() == null ? null : scanImport.getFormat().name());
        values.put("filename", scanImport.getOriginalFilename());
        values.put("sizeBytes", scanImport.getSizeBytes());
        values.put("totalFindings", scanImport.getTotalFindings());
        values.put("matchedCount", scanImport.getMatchedCount());
        values.put("unmatchedCount", scanImport.getUnmatchedCount());
        values.put("duplicateCount", scanImport.getDuplicateCount());
        values.put("importedCount", scanImport.getImportedCount());
        values.put("skippedCount", scanImport.getSkippedCount());
        return values;
    }
}
