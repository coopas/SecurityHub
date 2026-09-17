package com.securityhub.audit;

public enum AuditAction {
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,
    /**
     * An already rotated refresh token was presented again outside the grace window and the
     * whole family was revoked. The audit_logs.action column is VARCHAR(32) with no CHECK, so
     * new values do not require a migration.
     */
    TOKEN_REUSE_DETECTED,
    REGISTER,
    CREATE,
    UPDATE,
    DELETE,
    STATUS_CHANGE,
    ASSIGN,
    COMMENT,
    PASSWORD_RESET,
    USER_INVITED,
    USER_UPDATED,
    EXPORT,
    SCAN_IMPORT
}
