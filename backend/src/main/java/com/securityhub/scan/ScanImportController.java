package com.securityhub.scan;

import com.securityhub.scan.dto.ScanFindingMappingRequest;
import com.securityhub.scan.dto.ScanFindingResponse;
import com.securityhub.scan.dto.ScanImportResponse;
import com.securityhub.scan.dto.ScanImportSummaryResponse;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * No {@code @PreAuthorize} here: the role matrix is enforced by {@link ScanImportService}, so
 * it also applies to callers that never go through HTTP. The tenant is never a request
 * parameter; it comes from the principal.
 */
@Tag(name = "Scan imports")
@RestController
@RequestMapping("/api/v1/scan-imports")
@RequiredArgsConstructor
public class ScanImportController {

    private final ScanImportService scanImportService;

    /**
     * {@code projectId} and {@code format} are {@code @RequestParam} and not
     * {@code @RequestPart}: a non-file field of a multipart body is exposed by the container as
     * a request parameter, so this accepts them as parts of the form — which is how the client
     * sends them — without refusing a caller that puts them on the query string instead.
     *
     * <p>An unknown {@code format} is a 400 before the service is reached, because the enum is
     * what the binder converts to.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Uploads a scanner report and stages the import (ADMIN or ANALYST). "
            + "Nothing is created until it is confirmed")
    public ResponseEntity<ScanImportResponse> upload(@AuthenticationPrincipal AuthenticatedUser current,
                                                     @RequestParam("projectId") Long projectId,
                                                     @RequestParam("format") ScanFormat format,
                                                     @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(scanImportService.upload(current, projectId, format, file));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Details an import of the caller's own company with all of its findings")
    public ScanImportResponse get(@AuthenticationPrincipal AuthenticatedUser current,
                                  @PathVariable Long id) {
        return scanImportService.preview(current, id);
    }

    @PatchMapping("/{id}/findings/{findingId}")
    @Operation(summary = "Maps an asset to an unmatched finding (ADMIN or ANALYST)")
    public ScanFindingResponse mapFinding(@AuthenticationPrincipal AuthenticatedUser current,
                                          @PathVariable Long id,
                                          @PathVariable Long findingId,
                                          @Valid @RequestBody ScanFindingMappingRequest request) {
        return scanImportService.mapFinding(current, id, findingId, request.getAssetId());
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Creates the vulnerabilities of the findings that have an asset "
            + "(ADMIN or ANALYST)")
    public ScanImportResponse confirm(@AuthenticationPrincipal AuthenticatedUser current,
                                      @PathVariable Long id) {
        return scanImportService.confirm(current, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Discards a pending import and removes the file (ADMIN or ANALYST)")
    public void discard(@AuthenticationPrincipal AuthenticatedUser current, @PathVariable Long id) {
        scanImportService.discard(current, id);
    }

    @GetMapping
    @Operation(summary = "Import history of the authenticated user's company")
    public PageResponse<ScanImportSummaryResponse> history(
            @AuthenticationPrincipal AuthenticatedUser current,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(scanImportService.history(current, pageable));
    }
}
