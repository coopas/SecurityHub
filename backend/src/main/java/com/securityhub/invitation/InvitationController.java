package com.securityhub.invitation;

import com.securityhub.auth.dto.AuthResponse;
import com.securityhub.invitation.dto.InvitationAcceptRequest;
import com.securityhub.invitation.dto.InvitationPreviewResponse;
import com.securityhub.invitation.dto.InvitationRequest;
import com.securityhub.invitation.dto.InvitationResponse;
import com.securityhub.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * No {@code @PreAuthorize} here: the matrix is enforced by {@link InvitationService}.
 *
 * The two {@code /accept} routes are public through {@code SecurityConfig.PUBLIC_ENDPOINTS},
 * which opens them without naming an HTTP method and therefore covers both the GET of the
 * preview and the POST of the acceptance.
 */
@Validated
@Tag(name = "Invitations")
@RestController
@RequestMapping("/api/v1/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @PostMapping
    @Operation(summary = "Invites someone into the authenticated company (ADMIN only)")
    public ResponseEntity<InvitationResponse> create(@AuthenticationPrincipal AuthenticatedUser current,
                                                     @Valid @RequestBody InvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invitationService.create(current, request));
    }

    @GetMapping
    @Operation(summary = "Lists the invitations of the authenticated company (ADMIN only)")
    public List<InvitationResponse> list(@AuthenticationPrincipal AuthenticatedUser current) {
        return invitationService.list(current);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revokes a pending invitation of the caller's own company (ADMIN only)")
    public void revoke(@AuthenticationPrincipal AuthenticatedUser current, @PathVariable Long id) {
        invitationService.revoke(current, id);
    }

    @SecurityRequirements
    @GetMapping("/accept")
    @Operation(summary = "Public preview of the invitation from the e-mail token")
    public InvitationPreviewResponse preview(@RequestParam @NotBlank String token) {
        return invitationService.preview(token);
    }

    @SecurityRequirements
    @PostMapping("/accept")
    @Operation(summary = "Accepts the invitation, creates the account and already returns "
            + "the session")
    public ResponseEntity<AuthResponse> accept(@Valid @RequestBody InvitationAcceptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invitationService.accept(request));
    }
}
