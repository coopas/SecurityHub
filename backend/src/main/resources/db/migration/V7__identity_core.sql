-- Sessões de refresh (ADR 0006). Uma família é uma sessão: o login cria a primeira linha e
-- cada refresh marca a apresentada como ROTATED e insere uma nova ACTIVE na mesma família.
CREATE TABLE refresh_tokens (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    -- VARCHAR(36) e não uuid: o Hibernate 5.6 mapeia java.util.UUID para uuid-binary, que o
    -- ddl-auto: validate rejeita contra uma coluna uuid a menos que a entidade carregue
    -- @Type("pg-uuid"). Um identificador opaco não precisa do tipo nativo para nada.
    family_id      VARCHAR(36)  NOT NULL,
    token_hash     VARCHAR(64)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    revoked_reason VARCHAR(20),
    expires_at     TIMESTAMPTZ  NOT NULL,
    used_at        TIMESTAMPTZ,
    revoked_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    -- Sem company_id: nenhuma consulta desta tabela é por tenant. O token é apresentado sem
    -- sessão (o portador ainda não está autenticado), então a empresa só pode vir da linha de
    -- users apontada pela FK — que é a fonte autoritativa. Uma cópia aqui seria um segundo
    -- lugar para a empresa divergir sem nenhum ganho de consulta.
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_refresh_tokens_status CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    CONSTRAINT ck_refresh_tokens_revoked_reason CHECK (revoked_reason IS NULL OR revoked_reason IN
        ('LOGOUT', 'REUSE_DETECTED', 'PASSWORD_RESET', 'USER_DEACTIVATED', 'ROLE_CHANGED')),
    -- Equivalência: revogado implica motivo e motivo implica revogado. Sem ela, uma revogação
    -- sem causa registrada seria indistinguível de uma rotação normal na investigação de um
    -- incidente, que é exatamente quando esta tabela é lida por um humano.
    CONSTRAINT ck_refresh_tokens_revocation CHECK ((status = 'REVOKED') = (revoked_reason IS NOT NULL)),
    -- O formato do digest é garantido aqui e não só no serviço: uma linha gravada por uma
    -- migração, um seed ou um importador futuro com o token em claro seria um vazamento
    -- silencioso, e esta CHECK a rejeita na escrita.
    CONSTRAINT ck_refresh_tokens_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

-- Único: o hash é a chave de busca do /auth/refresh e uma colisão de linhas tornaria o
-- resultado da rotação ambíguo.
CREATE UNIQUE INDEX uk_refresh_tokens_hash ON refresh_tokens (token_hash);
-- Revogação em massa por usuário (logout de tudo, troca de papel, desativação, senha nova).
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
-- Revogação da família inteira quando um token já rotacionado é reapresentado.
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
-- Coleta preguiçosa das linhas vencidas no caminho do login.
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- Link de redefinição de senha. Usar o token APAGA a linha: não há máquina de estados porque
-- o único estado além de "pendente" é "não existe mais".
CREATE TABLE password_reset_tokens (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    -- No máximo um link vivo por usuário: pedir de novo invalida o anterior, então quem pedir
    -- duas vezes e clicar no primeiro e-mail recebe o mesmo erro genérico de link inválido.
    CONSTRAINT uk_password_reset_tokens_user UNIQUE (user_id),
    CONSTRAINT ck_password_reset_tokens_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uk_password_reset_tokens_hash ON password_reset_tokens (token_hash);

-- Convite para entrar em uma empresa. A linha de users só nasce no aceite, o que é o motivo
-- de o convite ser uma tabela separada: um convite de ADMIN pendente não pode contar como
-- administrador ativo na regra do "último administrador".
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
    -- Sem ON DELETE CASCADE, como em todo o esquema: quem convidou faz parte do registro e
    -- remover a pessoa não pode apagar o rastro de quem abriu a porta.
    CONSTRAINT fk_invitations_invited_by FOREIGN KEY (invited_by) REFERENCES users (id),
    CONSTRAINT ck_invitations_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_invitations_role CHECK (role IN ('ADMIN', 'ANALYST', 'DEVELOPER', 'VIEWER')),
    CONSTRAINT ck_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    CONSTRAINT ck_invitations_accepted_at CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL)),
    CONSTRAINT ck_invitations_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uk_invitations_hash ON invitations (token_hash);

-- Parcial e GLOBAL, não por empresa. users.email é único no produto inteiro (ADR 0004), então
-- dois convites vivos para o mesmo endereço em empresas diferentes seriam uma corrida cujo
-- perdedor descobriria o problema só no aceite, na forma de uma violação de constraint crua.
-- Barrando no convite, o segundo convidante recebe o mesmo 409 de "e-mail já cadastrado" e
-- não fica sabendo em qual empresa o endereço já está prometido.
CREATE UNIQUE INDEX uk_invitations_pending_email ON invitations (email) WHERE status = 'PENDING';

-- Cobre a listagem da tela de administração: os convites de uma empresa, filtráveis por estado.
CREATE INDEX idx_invitations_company_status ON invitations (company_id, status);
