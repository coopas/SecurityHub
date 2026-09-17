package com.securityhub.attachment;

import com.securityhub.attachment.dto.AttachmentDownload;
import com.securityhub.attachment.dto.AttachmentResponse;
import com.securityhub.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Nested under the vulnerability because an attachment has no meaning on its own.
 *
 * <p>No {@code @PreAuthorize} here: the role matrix is enforced by {@link AttachmentService},
 * so it also applies to callers that never go through HTTP. The tenant is never a request
 * parameter; it comes from the principal.
 */
@Tag(name = "Anexos")
@RestController
@RequestMapping("/api/v1/vulnerabilities/{vulnerabilityId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /**
     * Answers a bare JSON array rather than the paginated envelope of docs/api-examples.md. The
     * same deviation {@code SeverityDistributionResponse} documents, and for the same reason:
     * the result is capped at {@link AttachmentService#MAX_ATTACHMENTS} by a rule the client
     * cannot change, so the envelope would be five constant fields around a list that is never
     * a page and a {@code page}/{@code size} the client could not act on.
     */
    @GetMapping
    @Operation(summary = "Lista os anexos de uma vulnerabilidade da própria empresa")
    public List<AttachmentResponse> list(@AuthenticationPrincipal AuthenticatedUser current,
                                         @PathVariable Long vulnerabilityId) {
        return attachmentService.list(current, vulnerabilityId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Anexa um arquivo a uma vulnerabilidade (ADMIN, ANALYST ou DEVELOPER). "
            + "O tipo é determinado pelo conteúdo, não pelo cabeçalho enviado")
    public ResponseEntity<AttachmentResponse> upload(@AuthenticationPrincipal AuthenticatedUser current,
                                                     @PathVariable Long vulnerabilityId,
                                                     @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attachmentService.upload(current, vulnerabilityId, file));
    }

    /**
     * Always an attachment download, never an inline render: together with the
     * {@code X-Content-Type-Options: nosniff} that {@code SecurityConfig} adds to every
     * response, that is what makes storing a text file whose content happens to be markup
     * harmless. The name goes out in the RFC 5987 form so accents survive the trip.
     */
    @GetMapping("/{attachmentId}/download")
    @Operation(summary = "Baixa o anexo; o nome devolvido é o nome sanitizado do envio")
    public ResponseEntity<ByteArrayResource> download(@AuthenticationPrincipal AuthenticatedUser current,
                                                      @PathVariable Long vulnerabilityId,
                                                      @PathVariable Long attachmentId) {
        AttachmentDownload attachment = attachmentService.download(current, vulnerabilityId, attachmentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(attachment.getFilename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(MediaType.parseMediaType(attachment.getContentType()))
                .contentLength(attachment.getContent().length)
                .body(new ByteArrayResource(attachment.getContent()));
    }

    @DeleteMapping("/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove um anexo; apenas quem enviou ou um ADMIN")
    public void delete(@AuthenticationPrincipal AuthenticatedUser current,
                       @PathVariable Long vulnerabilityId,
                       @PathVariable Long attachmentId) {
        attachmentService.delete(current, vulnerabilityId, attachmentId);
    }
}
