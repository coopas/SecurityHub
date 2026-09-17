CREATE TABLE projects (
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT        NOT NULL,
    name        VARCHAR(140)  NOT NULL,
    description VARCHAR(2000),
    status      VARCHAR(20)   NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_projects_company FOREIGN KEY (company_id) REFERENCES companies (id),
    -- Nullable and ON DELETE unset by design: removing the author must not remove the
    -- project, and the audit trail keeps the authorship record either way.
    CONSTRAINT fk_projects_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_projects_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

-- Expression index rather than a table constraint: uniqueness is case-insensitive and
-- scoped to the company, so "Portal" and "portal" collide inside a tenant but never
-- across tenants.
CREATE UNIQUE INDEX uk_projects_company_name ON projects (company_id, lower(name));

CREATE INDEX idx_projects_company ON projects (company_id);
CREATE INDEX idx_projects_company_status ON projects (company_id, status);
