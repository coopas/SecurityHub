CREATE TABLE companies (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    slug        VARCHAR(140) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_companies_slug UNIQUE (slug),
    CONSTRAINT ck_companies_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    company_id    BIGINT       NOT NULL,
    name          VARCHAR(120) NOT NULL,
    email         VARCHAR(180) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_users_company FOREIGN KEY (company_id) REFERENCES companies (id),
    -- Global, not per-company: POST /auth/login receives only e-mail and password, so a
    -- repeated address across companies would make the credential ambiguous (ADR 0004).
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'ANALYST', 'DEVELOPER', 'VIEWER'))
);

CREATE INDEX idx_users_company ON users (company_id);
CREATE INDEX idx_users_company_active ON users (company_id, active);
