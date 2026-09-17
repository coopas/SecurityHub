CREATE TABLE audit_logs (
    id             BIGSERIAL PRIMARY KEY,
    company_id     BIGINT      NOT NULL,
    actor_id       BIGINT,
    actor_email    VARCHAR(180),
    action         VARCHAR(32) NOT NULL,
    entity_type    VARCHAR(40) NOT NULL,
    entity_id      BIGINT,
    old_value_json TEXT,
    new_value_json TEXT,
    ip_address     VARCHAR(45),
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_audit_logs_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_id) REFERENCES users (id)
);

CREATE INDEX idx_audit_logs_company_created ON audit_logs (company_id, created_at DESC);
CREATE INDEX idx_audit_logs_company_entity ON audit_logs (company_id, entity_type, entity_id);
CREATE INDEX idx_audit_logs_company_actor ON audit_logs (company_id, actor_id);
