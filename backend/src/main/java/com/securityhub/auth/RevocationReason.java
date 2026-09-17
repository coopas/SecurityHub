package com.securityhub.auth;

/**
 * Why a row stopped being valid. It exists for the investigation of an incident: without the
 * reason, a family killed by a theft would be indistinguishable from a logout when reading the
 * table.
 */
public enum RevocationReason {

    LOGOUT,
    REUSE_DETECTED,
    PASSWORD_RESET,
    USER_DEACTIVATED,
    ROLE_CHANGED
}
