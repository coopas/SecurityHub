package com.securityhub.attachment;

import com.securityhub.attachment.dto.AttachmentDownload;
import com.securityhub.attachment.dto.AttachmentResponse;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.error.BadRequestException;
import com.securityhub.shared.error.ConflictException;
import com.securityhub.shared.error.ForbiddenException;
import com.securityhub.shared.error.NotFoundException;
import com.securityhub.shared.error.PayloadTooLargeException;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import com.securityhub.vulnerability.Vulnerability;
import com.securityhub.vulnerability.VulnerabilityRepository;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attachments hang from a vulnerability, so every operation first proves the parent belongs to
 * the caller's company — a parent from another tenant is a 404 before anything else can
 * observe that the attachment exists.
 *
 * <p>Authorization is on this class and not on the controller, like everywhere else in the
 * project, so the role matrix also applies to callers that never go through HTTP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    static final String ENTITY_TYPE = "Attachment";

    /**
     * Per vulnerability. Evidence of a finding is a handful of files; a listing that grew
     * without bound would also make the "bare array" answer of the listing endpoint dishonest.
     */
    static final int MAX_ATTACHMENTS = 20;

    private final AttachmentRepository attachmentRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final UserRepository userRepository;
    private final AttachmentStorage attachmentStorage;
    private final AttachmentProperties attachmentProperties;
    private final AuditService auditService;

    /** Reading is open to every role, like the vulnerability itself (docs/permissions.md). */
    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(AuthenticatedUser current, Long vulnerabilityId) {
        requireVulnerability(current, vulnerabilityId);
        List<Attachment> attachments = attachmentRepository
                .findByCompanyIdAndVulnerabilityIdOrderByCreatedAtAscIdAsc(current.getCompanyId(),
                        vulnerabilityId, PageRequest.of(0, MAX_ATTACHMENTS));
        List<AttachmentResponse> responses = new ArrayList<>(attachments.size());
        for (Attachment attachment : attachments) {
            responses.add(AttachmentMapper.toResponse(attachment, current));
        }
        return responses;
    }

    /**
     * DEVELOPER is allowed here for the same reason they may comment (docs/permissions.md):
     * attaching the screenshot that proves a fix is the same act as writing "corrigido" under
     * the finding, and splitting the two would only push people back to pasting links.
     *
     * <p>The order of the checks below is the design, not housekeeping. Everything that can
     * refuse the request happens before a single byte reaches the disk, and the write itself
     * is the last thing before the row. Read it top to bottom:
     *
     * <ol>
     * <li>parent — cross-tenant is 404 first, so nothing after it can leak existence;</li>
     * <li>count — 409, because the cap is a state conflict, not bad input;</li>
     * <li>empty — 400;</li>
     * <li>size — 413, from the application limit, which may be below the container's;</li>
     * <li>sniff — 415, from the bytes, never from the declared header;</li>
     * <li>name, checksum, generated storage name;</li>
     * <li>write the file, then save the row.</li>
     * </ol>
     *
     * <p>That last order is the one worth defending, because it looks backwards. The two
     * failure modes are not symmetric. A row whose file is missing is a visible, broken
     * attachment: it is in the listing, someone clicks it, and the download fails. A file
     * whose row is missing is invisible — nothing references it, nothing lists the directory,
     * and it is found only when the volume fills up. So the file is written first and the row
     * second, leaving only the harmless direction possible, and an {@code afterCompletion}
     * hook removes the file whenever the transaction ends any way other than a commit.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','DEVELOPER')")
    public AttachmentResponse upload(AuthenticatedUser current, Long vulnerabilityId, MultipartFile file) {
        Vulnerability vulnerability = requireVulnerability(current, vulnerabilityId);

        long existing = attachmentRepository.countByCompanyIdAndVulnerabilityId(current.getCompanyId(),
                vulnerabilityId);
        if (existing >= MAX_ATTACHMENTS) {
            throw new ConflictException("A vulnerabilidade já possui o máximo de " + MAX_ATTACHMENTS
                    + " anexos; remova um anexo antes de enviar outro");
        }

        if (file == null || file.isEmpty()) {
            throw new BadRequestException("O arquivo enviado está vazio");
        }
        if (file.getSize() > attachmentProperties.getMaxSizeBytes()) {
            throw new PayloadTooLargeException("O arquivo excede o limite de "
                    + attachmentProperties.getMaxSizeBytes() + " bytes");
        }

        byte[] content = read(file);
        if (content.length == 0) {
            throw new BadRequestException("O arquivo enviado está vazio");
        }
        // Re-checked against the bytes actually read: getSize reports what the part declared.
        if (content.length > attachmentProperties.getMaxSizeBytes()) {
            throw new PayloadTooLargeException("O arquivo excede o limite de "
                    + attachmentProperties.getMaxSizeBytes() + " bytes");
        }

        String contentType = AttachmentContentTypeDetector.detect(content);
        String originalFilename = AttachmentFilenameSanitizer.sanitize(file.getOriginalFilename());
        String checksum = sha256Hex(content);
        String storedFilename = UUID.randomUUID().toString().replace("-", "");
        User uploader = requireUploader(current);

        attachmentStorage.store(storedFilename, content);
        deleteFileUnlessCommitted(storedFilename);

        Attachment attachment = new Attachment(vulnerability.getCompany(), vulnerability, uploader,
                originalFilename, storedFilename, contentType, content.length, checksum);
        attachmentRepository.save(attachment);

        auditService.record(AuditEntry.created(current, ENTITY_TYPE, attachment.getId(),
                snapshot(attachment)));
        log.info("Anexo {} enviado na vulnerabilidade {} da empresa {}", attachment.getId(),
                vulnerabilityId, current.getCompanyId());
        return AttachmentMapper.toResponse(attachment, current);
    }

    /**
     * Any authenticated member of the company, like reading the vulnerability itself. The
     * bytes are read inside the transaction and handed to the controller as a value, so the
     * response can be built after it closes.
     */
    @Transactional(readOnly = true)
    public AttachmentDownload download(AuthenticatedUser current, Long vulnerabilityId, Long attachmentId) {
        Attachment attachment = require(current, vulnerabilityId, attachmentId);
        return new AttachmentDownload(attachment.getOriginalFilename(), attachment.getContentType(),
                attachmentStorage.load(attachment.getStoredFilename()));
    }

    /**
     * The annotation is the coarse gate — it keeps VIEWER out entirely. The finer rule, "the
     * uploader or an ADMIN", needs the row, so it lives in the body and runs only after the
     * row has been loaded within the caller's company. That ordering is what makes a
     * cross-tenant id a 404 instead of a 403 that would confirm the id exists somewhere.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','DEVELOPER')")
    public void delete(AuthenticatedUser current, Long vulnerabilityId, Long attachmentId) {
        Attachment attachment = require(current, vulnerabilityId, attachmentId);
        if (!AttachmentMapper.canDelete(attachment, current)) {
            throw new ForbiddenException("Apenas quem enviou o anexo ou um administrador pode removê-lo");
        }

        Map<String, Object> before = snapshot(attachment);
        String storedFilename = attachment.getStoredFilename();
        attachmentRepository.delete(attachment);

        auditService.record(AuditEntry.deleted(current, ENTITY_TYPE, attachmentId, before));
        deleteFileAfterCommit(storedFilename);
        log.info("Anexo {} removido da vulnerabilidade {} na empresa {}", attachmentId, vulnerabilityId,
                current.getCompanyId());
    }

    private Vulnerability requireVulnerability(AuthenticatedUser current, Long vulnerabilityId) {
        return vulnerabilityRepository.findByIdAndCompanyId(vulnerabilityId, current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Vulnerabilidade", vulnerabilityId));
    }

    /**
     * The parent is proved first so an attachment reached through the wrong vulnerability, or
     * through another company, is the same 404 as one that does not exist.
     */
    private Attachment require(AuthenticatedUser current, Long vulnerabilityId, Long attachmentId) {
        requireVulnerability(current, vulnerabilityId);
        return attachmentRepository
                .findByIdAndCompanyIdAndVulnerabilityId(attachmentId, current.getCompanyId(), vulnerabilityId)
                .orElseThrow(() -> NotFoundException.of("Anexo", attachmentId));
    }

    private User requireUploader(AuthenticatedUser current) {
        return userRepository.findByIdAndCompanyId(current.getId(), current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Usuário", current.getId()));
    }

    private byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BadRequestException("Não foi possível ler o arquivo enviado");
        }
    }

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
     * The row is gone only once the transaction commits, so the file must not disappear before
     * that: a rollback after the delete would otherwise leave a row pointing at nothing.
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
            attachmentStorage.delete(storedFilename);
        } catch (RuntimeException ex) {
            log.warn("Arquivo de anexo {} não pôde ser {}", storedFilename, reason, ex);
        }
    }

    /**
     * The trail records what the file is, never what it contains: name, sniffed type, size and
     * digest. The digest is what lets an auditor prove later that a downloaded file is the one
     * that was uploaded, without the trail ever holding a copy of it.
     */
    private Map<String, Object> snapshot(Attachment attachment) {
        Map<String, Object> values = AuditEntry.values();
        values.put("vulnerabilityId", attachment.getVulnerability().getId());
        values.put("filename", attachment.getOriginalFilename());
        values.put("contentType", attachment.getContentType());
        values.put("sizeBytes", attachment.getSizeBytes());
        values.put("checksumSha256", attachment.getChecksumSha256());
        return values;
    }

    private String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", current));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível nesta JVM", ex);
        }
    }
}
