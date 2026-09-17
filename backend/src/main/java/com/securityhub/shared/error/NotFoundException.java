package com.securityhub.shared.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, message);
    }

    /**
     * Used for tenant-scoped lookups: a resource that belongs to another company must be
     * indistinguishable from a resource that does not exist.
     */
    public static NotFoundException of(String resource, Object id) {
        return new NotFoundException(resource + " " + id + " não encontrado");
    }
}
