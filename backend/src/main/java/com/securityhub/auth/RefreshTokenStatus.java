package com.securityhub.auth;

public enum RefreshTokenStatus {

    /** Issued and not presented yet. At most one per family under normal operation. */
    ACTIVE,

    /** Already exchanged for a successor. Presenting it again outside the grace window is reuse. */
    ROTATED,

    /** Killed by an explicit event; always accompanied by a {@link RevocationReason}. */
    REVOKED
}
