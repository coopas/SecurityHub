package com.securityhub.shared.error;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiError {

    private final Instant timestamp;
    private final int status;
    private final String code;
    private final String message;
    private final String path;
    private final List<ApiFieldError> fieldErrors;
    private final String traceId;
}
