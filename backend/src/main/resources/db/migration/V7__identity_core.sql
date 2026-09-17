-- Refresh sessions (ADR 0006). A family is a session: the login creates the first row and every
-- refresh marks the presented one ROTATED and inserts a new ACTIVE one in the same family.
CREATE TABLE refresh_tokens (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    -- VARCHAR(36) and not uuid: Hibernate 5.6 maps java.util.UUID to uuid-binary, which
    -- ddl-auto: validate rejects against a uuid column unless the entity carries
    -- @Type("pg-uuid"). An opaque identifier has no use for the native type.
    family_id      VARCHAR(36)  NOT NULL,
    token_hash     VARCHAR(64)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    revoked_reason VARCHAR(20),
    expires_at     TIMESTAMPTZ  NOT NULL,
    used_at        TIMESTAMPTZ,
    revoked_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    -- No company_id: no query of this table is per tenant. The token is presented without a
    -- session (the bearer is not authenticated yet), so the company can only come from the
    -- users row the FK points at — which is the authoritative source. A copy here would be a
    -- second place for the company to diverge, with no query gain at all.
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_refresh_tokens_status CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    CONSTRAINT ck_refresh_tokens_revoked_reason CHECK (revoked_reason IS NULL OR revoked_reason IN
        ('LOGOUT', 'REUSE_DETECTED', 'PASSWORD_RESET', 'USER_DEACTIVATED', 'ROLE_CHANGED')),
    -- An equivalence: revoked implies a reason and a reason implies revoked. Without it, a
    -- revocation with no recorded cause would be indistinguishable from an ordinary rotation
    -- during the investigation of an incident, which is exactly when a human reads this table.
    CONSTRAINT ck_refresh_tokens_revocation CHECK ((status = 'REVOKED') = (revoked_reason IS NOT NULL)),
    -- The digest format is guaranteed here and not only in the service: a row written by a
    -- migration, a seed or a future importer with the token in the clear would be a silent
    -- leak, and this CHECK rejects it on write.
    CONSTRAINT ck_refresh_tokens_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

-- Unique: the hash is the lookup key of /auth/refresh and a collision between rows would make
-- the result of the rotation ambiguous.
CREATE UNIQUE INDEX uk_refresh_tokens_hash ON refresh_tokens (token_hash);
-- Bulk revocation per user (log out everywhere, role change, deactivation, new password).
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
-- Revocation of the whole family when an already rotated token is presented again.
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
-- Lazy collection of expired rows on the login path.
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- Password reset link. Using the token DELETES the row: there is no state machine because the
-- only state besides "pending" is "no longer exists".
CREATE TABLE password_reset_tokens (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    -- At most one live link per user: asking again invalidates the previous one, so whoever
    -- asks twice and clicks the first e-mail gets the same generic invalid-link error.
    CONSTRAINT uk_password_reset_tokens_user UNIQUE (user_id),
    CONSTRAINT ck_password_reset_tokens_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uk_password_reset_tokens_hash ON password_reset_tokens (token_hash);

-- Invitation to join a company. The users row is only born on acceptance, which is the reason
-- the invitation is a separate table: a pending ADMIN invitation must not count as an active
-- administrator in the "last administrator" rule.
CREATE TABLE invitations (
    id          BIGSERIAL    PRIMARY KEY,
    company_id  BIGINT       NOT NULL,
    name        VARCHAR(120) NOT NULL,
    email       VARCHAR(180) NOT NULL,
    role        VARCHAR(20)  NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    accepted_at TIMESTAMPTZ,
    invited_by  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_invitations_company FOREIGN KEY (company_id) REFERENCES companies (id),
    -- No ON DELETE CASCADE, as everywhere in this schema: the inviter is part of the record
    -- and removing that person must not erase the trace of who opened the door.
    CONSTRAINT fk_invitations_invited_by FOREIGN KEY (invited_by) REFERENCES users (id),
    CONSTRAINT ck_invitations_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_invitations_role CHECK (role IN ('ADMIN', 'ANALYST', 'DEVELOPER', 'VIEWER')),
    CONSTRAINT ck_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    CONSTRAINT ck_invitations_accepted_at CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL)),
    CONSTRAINT ck_invitations_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uk_invitations_hash ON invitations (token_hash);

-- Partial and GLOBAL, not per company. users.email is unique across the whole product (ADR
-- 0004), so two live invitations for the same address in different companies would be a race
-- whose loser would only find out on acceptance, in the form of a raw constraint violation.
-- Blocking it at the invitation, the second inviter gets the same 409 of "e-mail já cadastrado"
-- and never learns which company the address is already promised to.
CREATE UNIQUE INDEX uk_invitations_pending_email ON invitations (email) WHERE status = 'PENDING';

-- Covers the listing of the administration screen: the invitations of one company, filterable
-- by status.
CREATE INDEX idx_invitations_company_status ON invitations (company_id, status);
