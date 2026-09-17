package com.securityhub.asset;

import com.securityhub.asset.dto.AssetRequest;
import com.securityhub.asset.dto.AssetResponse;
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

@Tag(name = "Assets")
@RestController
@RequestMapping("/api/v1/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @GetMapping
    @Operation(summary = "Lists the assets of the authenticated user's company")
    public PageResponse<AssetResponse> list(@AuthenticationPrincipal AuthenticatedUser current,
                                            @RequestParam(required = false) String search,
                                            @RequestParam(required = false) Long projectId,
                                            @RequestParam(required = false) AssetType type,
                                            @RequestParam(required = false) Environment environment,
                                            @RequestParam(required = false) Criticality criticality,
                                            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(assetService.search(current, search, projectId, type, environment,
                criticality, pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Creates an asset in a project of the caller's own company (ADMIN only)")
    public ResponseEntity<AssetResponse> create(@AuthenticationPrincipal AuthenticatedUser current,
                                                @Valid @RequestBody AssetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(assetService.create(current, request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Returns the details of an asset of the caller's own company")
    public AssetResponse get(@AuthenticationPrincipal AuthenticatedUser current, @PathVariable Long id) {
        return assetService.get(current, id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Updates an asset (ADMIN only)")
    public AssetResponse update(@AuthenticationPrincipal AuthenticatedUser current,
                                @PathVariable Long id,
                                @Valid @RequestBody AssetRequest request) {
        return assetService.update(current, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deletes an asset that has no vulnerabilities (ADMIN only)")
    public void delete(@AuthenticationPrincipal AuthenticatedUser current, @PathVariable Long id) {
        assetService.delete(current, id);
    }
}
