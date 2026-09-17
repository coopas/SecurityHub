package com.securityhub.project;

import com.securityhub.project.dto.ProjectRequest;
import com.securityhub.project.dto.ProjectResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Projetos")
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    @Operation(summary = "Lista projetos da empresa do usuário autenticado")
    public PageResponse<ProjectResponse> list(@AuthenticationPrincipal AuthenticatedUser current,
                                              @RequestParam(required = false) String search,
                                              @RequestParam(required = false) ProjectStatus status,
                                              @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(projectService.search(current, search, status, pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria um projeto (somente ADMIN)")
    public ResponseEntity<ProjectResponse> create(@AuthenticationPrincipal AuthenticatedUser current,
                                                  @Valid @RequestBody ProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(current, request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalha um projeto da própria empresa")
    public ProjectResponse get(@AuthenticationPrincipal AuthenticatedUser current, @PathVariable Long id) {
        return projectService.get(current, id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza um projeto (somente ADMIN)")
    public ProjectResponse update(@AuthenticationPrincipal AuthenticatedUser current,
                                  @PathVariable Long id,
                                  @Valid @RequestBody ProjectRequest request) {
        return projectService.update(current, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Exclui um projeto sem filhos (somente ADMIN)")
    public void delete(@AuthenticationPrincipal AuthenticatedUser current, @PathVariable Long id) {
        projectService.delete(current, id);
    }
}
