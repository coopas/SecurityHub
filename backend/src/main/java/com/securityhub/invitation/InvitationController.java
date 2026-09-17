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
 * Sem {@code @PreAuthorize}: a matriz é aplicada por {@link InvitationService}.
 *
 * As duas rotas de {@code /accept} são públicas por {@code SecurityConfig.PUBLIC_ENDPOINTS},
 * que as libera sem citar método HTTP e portanto cobre o GET da prévia e o POST do aceite.
 */
@Validated
@Tag(name = "Convites")
@RestController
@RequestMapping("/api/v1/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @PostMapping
    @Operation(summary = "Convida alguém para a empresa autenticada (somente ADMIN)")
    public ResponseEntity<InvitationResponse> create(@AuthenticationPrincipal AuthenticatedUser current,
                                                     @Valid @RequestBody InvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invitationService.create(current, request));
    }

    @GetMapping
    @Operation(summary = "Lista os convites da empresa autenticada (somente ADMIN)")
    public List<InvitationResponse> list(@AuthenticationPrincipal AuthenticatedUser current) {
        return invitationService.list(current);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoga um convite pendente da própria empresa (somente ADMIN)")
    public void revoke(@AuthenticationPrincipal AuthenticatedUser current, @PathVariable Long id) {
        invitationService.revoke(current, id);
    }

    @SecurityRequirements
    @GetMapping("/accept")
    @Operation(summary = "Prévia pública do convite a partir do token do e-mail")
    public InvitationPreviewResponse preview(@RequestParam @NotBlank String token) {
        return invitationService.preview(token);
    }

    @SecurityRequirements
    @PostMapping("/accept")
    @Operation(summary = "Aceita o convite, cria a conta e já devolve a sessão")
    public ResponseEntity<AuthResponse> accept(@Valid @RequestBody InvitationAcceptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invitationService.accept(request));
    }
}
