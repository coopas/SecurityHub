CREATE TABLE vulnerabilities (
    id            BIGSERIAL PRIMARY KEY,
    company_id    BIGINT        NOT NULL,
    asset_id      BIGINT        NOT NULL,
    title         VARCHAR(200)  NOT NULL,
    description   VARCHAR(4000),
    severity      VARCHAR(20)   NOT NULL,
    cvss_score    NUMERIC(3, 1),
    cve           VARCHAR(20),
    status        VARCHAR(20)   NOT NULL,
    discovered_at TIMESTAMPTZ   NOT NULL,
    due_date      TIMESTAMPTZ,
    resolved_at   TIMESTAMPTZ,
    assigned_to   BIGINT,
    created_by    BIGINT,
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL,
    -- company_id is denormalized exactly like on assets: every domain query filters by
    -- tenant and reaching the company through assets -> projects would put two joins on
    -- the hot path of the busiest table of the product.
    --
    -- project_id is deliberately NOT denormalized here. AssetService.update lets an ADMIN
    -- move an asset to another project of the same company, so a copy of the project would
    -- go stale on the next move; the project is always read through asset.project.
    CONSTRAINT fk_vulnerabilities_company FOREIGN KEY (company_id) REFERENCES companies (id),
    -- No ON DELETE CASCADE: docs/permissions.md requires a conflict when the parent still has
    -- children, so AssetService.ensureNoChildren stays the visible owner of that rule.
    CONSTRAINT fk_vulnerabilities_asset FOREIGN KEY (asset_id) REFERENCES assets (id),
    -- Nullable: an unassigned vulnerability is a normal state, and removing a user must
    -- not remove their findings.
    CONSTRAINT fk_vulnerabilities_assigned_to FOREIGN KEY (assigned_to) REFERENCES users (id),
    CONSTRAINT fk_vulnerabilities_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_vulnerabilities_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_vulnerabilities_status
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'ACCEPTED_RISK')),
    -- NUMERIC(3,1) already caps the magnitude, but the range of docs/data-model.md is 0.0..10.0 and
    -- the type alone would happily accept 99.9.
    CONSTRAINT ck_vulnerabilities_cvss
        CHECK (cvss_score IS NULL OR (cvss_score >= 0 AND cvss_score <= 10)),
    -- The service normalizes (trim, blank -> NULL, uppercase); the database is what
    -- guarantees the format for rows written by a migration, a seed or a future importer.
    CONSTRAINT ck_vulnerabilities_cve CHECK (cve IS NULL OR cve ~ '^CVE-[0-9]{4}-[0-9]{4,}$'),
    -- The resolvedAt rule of docs/data-model.md expressed as an equivalence between two booleans:
    -- RESOLVED implies a resolution instant and every other status implies its absence.
    -- Enforced here, and not only in the service, so a bug in a status transition cannot
    -- persist a vulnerability that claims to be resolved without saying when.
    CONSTRAINT ck_vulnerabilities_resolved_at CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

-- Every index is prefixed by company_id because no query of this table ever crosses the
-- tenant boundary: the prefix keeps the index usable for the filter that is always present.
CREATE INDEX idx_vulnerabilities_company_asset ON vulnerabilities (company_id, asset_id);
CREATE INDEX idx_vulnerabilities_company_status ON vulnerabilities (company_id, status);
CREATE INDEX idx_vulnerabilities_company_severity ON vulnerabilities (company_id, severity);
-- Matches the default ordering of the listing (createdAt,desc).
CREATE INDEX idx_vulnerabilities_company_created_at ON vulnerabilities (company_id, created_at DESC);
-- Feeds the dashboard trend, which buckets findings by discovery date.
CREATE INDEX idx_vulnerabilities_company_discovered_at ON vulnerabilities (company_id, discovered_at);

-- Partial: "assigned to me" is a frequent filter and most rows of a healthy backlog have
-- no owner, so indexing the NULLs would only make the index bigger.
CREATE INDEX idx_vulnerabilities_company_assigned_to
    ON vulnerabilities (company_id, assigned_to)
    WHERE assigned_to IS NOT NULL;

-- Partial on the exact definition of "overdue" used by the list filter and by the future
-- dashboard card: due_date IS NOT NULL AND due_date < now AND status IN (OPEN,IN_PROGRESS).
-- Resolved and accepted findings are never late, so they do not belong in the index.
CREATE INDEX idx_vulnerabilities_company_due_date
    ON vulnerabilities (company_id, due_date)
    WHERE status IN ('OPEN', 'IN_PROGRESS');

CREATE TABLE comments (
    id               BIGSERIAL PRIMARY KEY,
    company_id       BIGINT         NOT NULL,
    vulnerability_id BIGINT         NOT NULL,
    author_id        BIGINT         NOT NULL,
    content          VARCHAR(2000)  NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    CONSTRAINT fk_comments_company FOREIGN KEY (company_id) REFERENCES companies (id),
    -- Again without ON DELETE CASCADE. VulnerabilityService.delete removes the comments
    -- explicitly and records how many were removed in the audit trail, because there is no
    -- physical deletion of a comment in the MVP (docs/data-model.md) and a 409 here would make any
    -- commented vulnerability permanently undeletable.
    CONSTRAINT fk_comments_vulnerability FOREIGN KEY (vulnerability_id) REFERENCES vulnerabilities (id),
    CONSTRAINT fk_comments_author FOREIGN KEY (author_id) REFERENCES users (id),
    -- NOT NULL alone would accept a comment made only of spaces.
    CONSTRAINT ck_comments_content CHECK (length(btrim(content)) > 0)
);

-- Covers the only read of the table: the comments of one vulnerability, oldest first.
CREATE INDEX idx_comments_company_vulnerability
    ON comments (company_id, vulnerability_id, created_at);
