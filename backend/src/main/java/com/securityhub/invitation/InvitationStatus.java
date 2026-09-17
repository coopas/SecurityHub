package com.securityhub.invitation;

public enum InvitationStatus {

    /** Live: can still be accepted. It is the state covered by the partial unique index of V7. */
    PENDING,

    ACCEPTED,

    /** Cancelled by an administrator, or replaced by a new invitation to the same address. */
    REVOKED
}
