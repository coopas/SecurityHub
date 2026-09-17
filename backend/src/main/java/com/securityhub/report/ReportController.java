package com.securityhub.report;

import com.securityhub.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * No {@code @PreAuthorize} here on purpose: the role matrix is enforced by {@link ReportService},
 * so it also applies to callers that never go through HTTP. The tenant is never a request
 * parameter; it comes from the principal.
 */
@Tag(name = "Relatórios")
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * The document is small enough to answer as a {@code byte[]}: it is built in memory anyway,
     * and streaming it would return control to the servlet after the transaction closed, which
     * with {@code open-in-view: false} is where lazy associations blow up mid-response.
     */
    @GetMapping(value = "/executive", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Relatório executivo em PDF da empresa do usuário autenticado (ADMIN ou "
            + "ANALYST). Não aceita intervalo de datas: todos os números são agregações do "
            + "momento da geração, as mesmas que o dashboard exibe")
    public ResponseEntity<byte[]> executive(@AuthenticationPrincipal AuthenticatedUser current) {
        ExecutiveReport report = reportService.generateExecutivePdf(current);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(report.getFilename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(report.getContent().length)
                .body(report.getContent());
    }
}
