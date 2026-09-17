package com.securityhub.shared.error;

import org.springframework.http.HttpStatus;

public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }
}
