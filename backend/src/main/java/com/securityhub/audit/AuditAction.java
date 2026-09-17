package com.securityhub.audit;

public enum AuditAction {
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,
    /**
     * Um refresh token já rotacionado foi reapresentado fora da janela de graça e a família
     * inteira foi revogada. A coluna audit_logs.action é VARCHAR(32) sem CHECK, então novos
     * valores não pedem migração.
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
