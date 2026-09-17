package com.securityhub.comment;

import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.comment.dto.CommentRequest;
import com.securityhub.comment.dto.CommentResponse;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.error.ForbiddenException;
import com.securityhub.shared.error.NotFoundException;
import com.securityhub.shared.web.PageableSupport;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import com.securityhub.vulnerability.Vulnerability;
import com.securityhub.vulnerability.VulnerabilityRepository;
import java.util.Collections;
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
 * Comments hang from a vulnerability, so every operation first proves that the parent
 * belongs to the caller's company — a parent from another tenant is a 404 before anything
 * else can observe that the comment exists.
 *
 * There is no deletion endpoint in the MVP (docs/data-model.md); the text disappears only when the
 * whole vulnerability is deleted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    static final String ENTITY_TYPE = "Comment";

    static final Set<String> SORTABLE_PROPERTIES = Collections.singleton("createdAt");

    /**
     * Ascending: a discussion reads oldest first, unlike the other listings of the API. The
     * id is a tiebreaker, because two comments posted in the same microsecond would
     * otherwise come back in an arbitrary order on every page.
     */
    static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

    private final CommentRepository commentRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    /** Reading is open to every role, like the vulnerability itself (docs/permissions.md). */
    @Transactional(readOnly = true)
    public Page<CommentResponse> list(AuthenticatedUser current, Long vulnerabilityId, Pageable pageable) {
        requireVulnerability(current, vulnerabilityId);
        Pageable sanitized = PageableSupport.sanitize(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT);
        return commentRepository
                .findByCompanyIdAndVulnerabilityId(current.getCompanyId(), vulnerabilityId, sanitized)
                .map(comment -> CommentMapper.toResponse(comment, current));
    }

    /** VIEWER is read-only (docs/permissions.md), so commenting stops at the annotation for them. */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','DEVELOPER')")
    public CommentResponse create(AuthenticatedUser current, Long vulnerabilityId, CommentRequest request) {
        Vulnerability vulnerability = requireVulnerability(current, vulnerabilityId);
        User author = userRepository.findByIdAndCompanyId(current.getId(), current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Usuário", current.getId()));

        Comment comment = new Comment(vulnerability.getCompany(), vulnerability, author,
                CommentMapper.normalizeContent(request.getContent()));
        commentRepository.save(comment);

        auditService.record(AuditEntry.changed(current, AuditAction.COMMENT, ENTITY_TYPE, comment.getId(),
                null, snapshot(comment)));
        log.info("Comentário {} criado na vulnerabilidade {} da empresa {}", comment.getId(),
                vulnerabilityId, current.getCompanyId());
        return CommentMapper.toResponse(comment, current);
    }

    /**
     * The annotation is the same coarse gate as on creation; the "apenas o autor ou ADMIN"
     * rule of docs/data-model.md needs the row and therefore lives in the body. The parent and the
     * comment are loaded first, so anything outside the caller's company is a 404 and the
     * 403 only ever means "this is not yours".
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','DEVELOPER')")
    public CommentResponse update(AuthenticatedUser current, Long vulnerabilityId, Long commentId,
                                  CommentRequest request) {
        requireVulnerability(current, vulnerabilityId);
        Comment comment = commentRepository
                .findByIdAndCompanyIdAndVulnerabilityId(commentId, current.getCompanyId(), vulnerabilityId)
                .orElseThrow(() -> NotFoundException.of("Comentário", commentId));

        if (!CommentMapper.canEdit(comment, current)) {
            throw new ForbiddenException("Apenas o autor ou um administrador pode editar o comentário");
        }

        Map<String, Object> before = snapshot(comment);
        comment.setContent(CommentMapper.normalizeContent(request.getContent()));
        commentRepository.save(comment);

        auditService.record(AuditEntry.updated(current, ENTITY_TYPE, comment.getId(), before,
                snapshot(comment)));
        return CommentMapper.toResponse(comment, current);
    }

    private Vulnerability requireVulnerability(AuthenticatedUser current, Long vulnerabilityId) {
        return vulnerabilityRepository.findByIdAndCompanyId(vulnerabilityId, current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Vulnerabilidade", vulnerabilityId));
    }

    /**
     * The trail records the length of the comment, never the text. AuditSanitizer masks by
     * key name, not by value, so a credential pasted into a comment body would land
     * readable in the trail of every ADMIN. Nothing is lost by leaving it out: comments have
     * no physical deletion in the MVP, so the text is always available at its own endpoint.
     */
    private Map<String, Object> snapshot(Comment comment) {
        Map<String, Object> values = AuditEntry.values();
        values.put("vulnerabilityId", comment.getVulnerability().getId());
        values.put("authorId", comment.getAuthor() == null ? null : comment.getAuthor().getId());
        values.put("contentLength", comment.getContent() == null ? 0 : comment.getContent().length());
        return values;
    }
}
