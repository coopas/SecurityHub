package com.securityhub.shared.error;

import org.springframework.http.HttpStatus;

/**
 * The uploaded bytes are not one of the accepted types. Thrown after sniffing the content,
 * never from the declared {@code Content-Type} of the part, which the client controls.
 */
public class UnsupportedMediaTypeException extends ApiException {

    public UnsupportedMediaTypeException(String message) {
        super(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE, message);
    }
}
