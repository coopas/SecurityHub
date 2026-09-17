package com.securityhub.auth;

import com.securityhub.auth.dto.AuthResponse;
import com.securityhub.auth.dto.LoginRequest;
import com.securityhub.auth.dto.PasswordResetConfirmRequest;
import com.securityhub.auth.dto.PasswordResetRequest;
import com.securityhub.auth.dto.RefreshTokenRequest;
import com.securityhub.auth.dto.RegisterRequest;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Autenticação")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    @SecurityRequirements
    @PostMapping("/register")
    @Operation(summary = "Cria uma empresa e o seu primeiro usuário administrador")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @SecurityRequirements
    @PostMapping("/login")
    @Operation(summary = "Autentica e devolve o access token")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @SecurityRequirements
    @PostMapping("/refresh")
    @Operation(summary = "Troca o refresh token por um novo par de tokens")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @SecurityRequirements
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Encerra a sessão do refresh token apresentado")
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
    }

    @SecurityRequirements
    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Envia o link de redefinição; responde 202 mesmo para e-mail desconhecido")
    public void requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.request(request);
    }

    @SecurityRequirements
    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Define a senha nova a partir do token do e-mail; não abre sessão")
    public void confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirm(request);
    }

    @GetMapping("/me")
    @Operation(summary = "Dados do usuário autenticado")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser current) {
        return authService.currentUser(current.getId());
    }
}
