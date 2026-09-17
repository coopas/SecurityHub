package com.securityhub.user;

import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.auth.RefreshTokenService;
import com.securityhub.auth.RevocationReason;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.error.ConflictException;
import com.securityhub.shared.error.NotFoundException;
import com.securityhub.user.dto.ActiveChangeRequest;
import com.securityhub.user.dto.RoleChangeRequest;
import com.securityhub.user.dto.UserResponse;
import com.securityhub.user.dto.UserUpdateRequest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    static final String ENTITY_TYPE = "User";
    static final String LAST_ADMIN = "A empresa precisa de ao menos um administrador ativo";
    static final String SELF_DEACTIVATION = "Você não pode desativar a própria conta";

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final RefreshTokenService refreshTokenService;

    /**
     * ANALYST is included because assigning a vulnerability (docs/permissions.md) requires choosing a
     * user; the endpoint is read-only and never leaves the caller's company.
     *
     * Devolve um array puro e não uma página. Não é esquecimento: dois serviços do frontend
     * consomem a resposta com um cast direto para {@code User[]}, e envelopá-la em
     * {@code PageResponse} quebraria os dois em tempo de execução, sem erro de compilação.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public List<UserResponse> search(AuthenticatedUser current, Role role, Boolean active, String search) {
        return userRepository
                .findAll(UserSpecifications.filter(current.getCompanyId(), role, active, search),
                        Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(UserMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse get(AuthenticatedUser current, Long id) {
        return UserMapper.toResponse(require(current, id));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse update(AuthenticatedUser current, Long id, UserUpdateRequest request) {
        User user = require(current, id);
        Map<String, Object> before = snapshot(user);

        user.setName(request.getName().trim());
        userRepository.save(user);

        auditService.record(AuditEntry.changed(current, AuditAction.USER_UPDATED, ENTITY_TYPE, id,
                before, snapshot(user)));
        return UserMapper.toResponse(user);
    }

    /**
     * Ordem das guardas, e ela importa:
     *
     * <ol>
     *   <li>{@code require} — um usuário de outra empresa é 404 antes de qualquer regra de
     *       posse, senão o 403 confirmaria que o id existe em algum lugar;</li>
     *   <li>último administrador ativo — rebaixar o único ADMIN ativo deixaria a empresa sem
     *       ninguém capaz de administrá-la, inclusive de promover um substituto;</li>
     *   <li>auto-rebaixamento é permitido quando existe outro ADMIN ativo, e a guarda acima
     *       já cobre o caso em que não existe.</li>
     * </ol>
     *
     * Um convite de ADMIN pendente não conta: a contagem é de linhas de {@code users} ativas,
     * e um convite ainda não é ninguém.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse changeRole(AuthenticatedUser current, Long id, RoleChangeRequest request) {
        User user = require(current, id);
        if (user.getRole() == request.getRole()) {
            return UserMapper.toResponse(user);
        }
        ensureNotLastActiveAdmin(current, user);

        Map<String, Object> before = snapshot(user);
        user.setRole(request.getRole());
        userRepository.save(user);
        // O papel viaja dentro do access token; mantê-lo válido deixaria a permissão antiga de
        // pé até o vencimento. O filtro já recusa o access token cujo papel divergiu da linha,
        // e revogar o refresh fecha o outro lado: renovar a sessão exige logar de novo.
        refreshTokenService.revokeAllForUser(user.getId(), RevocationReason.ROLE_CHANGED);

        UserResponse response = UserMapper.toResponse(user);
        auditService.record(AuditEntry.changed(current, AuditAction.USER_UPDATED, ENTITY_TYPE, id,
                before, snapshot(user)));
        log.info("Papel do usuário {} alterado para {} na empresa {}", id, request.getRole(),
                current.getCompanyId());
        return response;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse changeActive(AuthenticatedUser current, Long id, ActiveChangeRequest request) {
        User user = require(current, id);
        boolean active = Boolean.TRUE.equals(request.getActive());
        if (user.isActive() == active) {
            return UserMapper.toResponse(user);
        }
        if (!active) {
            ensureNotLastActiveAdmin(current, user);
            // Proibido sempre, mesmo havendo outros administradores. Desativar a si mesmo é um
            // tiro no pé irreversível pela própria API — a conta perde o acesso que precisaria
            // para se reativar — e nunca é o que alguém quis fazer.
            if (user.getId().equals(current.getId())) {
                throw new ConflictException(SELF_DEACTIVATION);
            }
        }

        Map<String, Object> before = snapshot(user);
        user.setActive(active);
        userRepository.save(user);
        if (!active) {
            refreshTokenService.revokeAllForUser(user.getId(), RevocationReason.USER_DEACTIVATED);
        }
        // Reativar não revoga nada: não há sessão para encerrar, e as linhas que existiam já
        // morreram na desativação.

        UserResponse response = UserMapper.toResponse(user);
        auditService.record(AuditEntry.changed(current, AuditAction.USER_UPDATED, ENTITY_TYPE, id,
                before, snapshot(user)));
        log.info("Usuário {} {} na empresa {}", id, active ? "reativado" : "desativado",
                current.getCompanyId());
        return response;
    }

    /**
     * Só dispara para um ADMIN ativo que está prestes a deixar de contar — rebaixado ou
     * desativado. Promover, rebaixar quem já não é ADMIN ou desativar quem já está inativo
     * não mexem na contagem.
     */
    private void ensureNotLastActiveAdmin(AuthenticatedUser current, User user) {
        if (user.getRole() != Role.ADMIN || !user.isActive()) {
            return;
        }
        if (userRepository.countByCompanyIdAndRoleAndActiveTrue(current.getCompanyId(), Role.ADMIN) <= 1) {
            throw new ConflictException(LAST_ADMIN);
        }
    }

    /** Usuário de outra empresa é 404, nunca 403: a API não confirma que o id existe. */
    private User require(AuthenticatedUser current, Long id) {
        return userRepository.findByIdAndCompanyId(id, current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Usuário", id));
    }

    /** Sem o hash da senha: o sanitizador o mascararia, e ele não tem nada que fazer aqui. */
    private Map<String, Object> snapshot(User user) {
        Map<String, Object> values = AuditEntry.values();
        values.put("name", user.getName());
        values.put("email", user.getEmail());
        values.put("role", user.getRole().name());
        values.put("active", user.isActive());
        return values;
    }
}
