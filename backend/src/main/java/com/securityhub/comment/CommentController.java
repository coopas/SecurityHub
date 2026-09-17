package com.securityhub.comment;

import com.securityhub.comment.dto.CommentRequest;
import com.securityhub.comment.dto.CommentResponse;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nested under the vulnerability because a comment has no meaning on its own; there is no
 * delete mapping because the MVP has no physical deletion of comments (docs/data-model.md).
 */
@Tag(name = "Comments")
@RestController
@RequestMapping("/api/v1/vulnerabilities/{vulnerabilityId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @GetMapping
    @Operation(summary = "Lists the comments of a vulnerability of the caller's own company")
    public PageResponse<CommentResponse> list(@AuthenticationPrincipal AuthenticatedUser current,
                                              @PathVariable Long vulnerabilityId,
                                              @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(commentService.list(current, vulnerabilityId, pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Comments on a vulnerability (ADMIN, ANALYST or DEVELOPER)")
    public ResponseEntity<CommentResponse> create(@AuthenticationPrincipal AuthenticatedUser current,
                                                  @PathVariable Long vulnerabilityId,
                                                  @Valid @RequestBody CommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.create(current, vulnerabilityId, request));
    }

    @PutMapping("/{commentId}")
    @Operation(summary = "Edits a comment; only the author or an ADMIN")
    public CommentResponse update(@AuthenticationPrincipal AuthenticatedUser current,
                                  @PathVariable Long vulnerabilityId,
                                  @PathVariable Long commentId,
                                  @Valid @RequestBody CommentRequest request) {
        return commentService.update(current, vulnerabilityId, commentId, request);
    }
}
