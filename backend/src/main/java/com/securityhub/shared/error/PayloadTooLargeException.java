package com.securityhub.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Raised when the application's own size limit rejects a body that the servlet container
 * already accepted. {@code MaxUploadSizeExceededException} covers the container limit; this
 * one covers {@code securityhub.attachments.max-size-bytes}, which may be lower.
 */
public class PayloadTooLargeException extends ApiException {

    public PayloadTooLargeException(String message) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE, message);
    }
}
