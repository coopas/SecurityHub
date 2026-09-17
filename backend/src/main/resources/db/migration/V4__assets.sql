CREATE TABLE assets (
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT        NOT NULL,
    project_id  BIGINT        NOT NULL,
    name        VARCHAR(140)  NOT NULL,
    description VARCHAR(2000),
    type        VARCHAR(20)   NOT NULL,
    identifier  VARCHAR(255),
    environment VARCHAR(20)   NOT NULL,
    criticality VARCHAR(20)   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    -- company_id is denormalized on purpose: every domain query filters by tenant and
    -- joining projects only to reach the company would put a join on the hot path.
    CONSTRAINT fk_assets_company FOREIGN KEY (company_id) REFERENCES companies (id),
    -- No ON DELETE CASCADE: docs/permissions.md requires a conflict when the parent still has
    -- children, so the deletion rule stays visible in the service instead of silently
    -- happening in the database.
    CONSTRAINT fk_assets_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT ck_assets_type CHECK (type IN ('API', 'SERVER', 'WEBSITE', 'DATABASE', 'WORKSTATION', 'OTHER')),
    CONSTRAINT ck_assets_environment CHECK (environment IN ('PRODUCTION', 'STAGING', 'DEVELOPMENT', 'TEST')),
    CONSTRAINT ck_assets_criticality CHECK (criticality IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

-- Partial and case-insensitive: the identifier is unique inside the project only when it
-- is filled (docs/data-model.md). A plain unique index would let a single NULL through in some
-- engines and would treat "10.0.0.1" and "10.0.0.1 " as distinct after trimming, so the
-- service normalizes blanks to NULL and the index enforces the rest.
CREATE UNIQUE INDEX uk_assets_project_identifier
    ON assets (project_id, lower(identifier))
    WHERE identifier IS NOT NULL;

CREATE INDEX idx_assets_company ON assets (company_id);
CREATE INDEX idx_assets_company_project ON assets (company_id, project_id);
CREATE INDEX idx_assets_company_criticality ON assets (company_id, criticality);
