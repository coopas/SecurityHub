package com.securityhub.user;

import com.securityhub.security.AuthenticatedUser;
import com.securityhub.user.dto.ActiveChangeRequest;
import com.securityhub.user.dto.RoleChangeRequest;
import com.securityhub.user.dto.UserResponse;
import com.securityhub.user.dto.UserUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sem {@code @PreAuthorize}: a matriz é aplicada por {@link UserService}. */
@Tag(name = "Usuários")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "Lista os usuários da empresa autenticada")
    public List<UserResponse> list(@AuthenticationPrincipal AuthenticatedUser current,
                                   @RequestParam(required = false) Role role,
                                   @RequestParam(required = false) Boolean active,
                                   @RequestParam(required = false) String search) {
        return userService.search(current, role, active, search);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalha um usuário da própria empresa (somente ADMIN)")
    public UserResponse get(@AuthenticationPrincipal AuthenticatedUser current, @PathVariable Long id) {
        return userService.get(current, id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Altera o nome de um usuário; o e-mail não é editável (somente ADMIN)")
    public UserResponse update(@AuthenticationPrincipal AuthenticatedUser current,
                               @PathVariable Long id,
                               @Valid @RequestBody UserUpdateRequest request) {
        return userService.update(current, id, request);
    }

    @PatchMapping("/{id}/role")
    @Operation(summary = "Altera o papel; encerra as sessões do usuário (somente ADMIN)")
    public UserResponse changeRole(@AuthenticationPrincipal AuthenticatedUser current,
                                   @PathVariable Long id,
                                   @Valid @RequestBody RoleChangeRequest request) {
        return userService.changeRole(current, id, request);
    }

    @PatchMapping("/{id}/active")
    @Operation(summary = "Ativa ou desativa; desativar encerra as sessões (somente ADMIN)")
    public UserResponse changeActive(@AuthenticationPrincipal AuthenticatedUser current,
                                     @PathVariable Long id,
                                     @Valid @RequestBody ActiveChangeRequest request) {
        return userService.changeActive(current, id, request);
    }
}
