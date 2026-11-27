-- V3: audit logs and in-app notifications (Phase 3)
-- NEVER edit this file after it has run. Add V{n}__*.sql instead.
-- (architecture.md planned these as V7__system.sql; Flyway needs ascending versions, so later files move up.
--  outbox_events and shedlock come with RabbitMQ in Phase 9.)

CREATE TABLE audit_logs (
    id          BIGSERIAL     PRIMARY KEY,
    public_id   UUID          NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    -- NULL = no logged-in user (system job, anonymous failed login)
    user_id     BIGINT        REFERENCES users (id),
    action      VARCHAR(20)   NOT NULL,
    entity      VARCHAR(50)   NOT NULL,
    -- the public identifier (UUID, slug, PO number), never the internal id
    entity_id   VARCHAR(100),
    details     JSONB,
    ip_address  VARCHAR(45),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_audit_logs_action
        CHECK (action IN ('CREATE', 'UPDATE', 'DELETE', 'LOGIN', 'LOGIN_FAILED', 'AI_ACTION'))
);

CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at DESC);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity, entity_id);
CREATE INDEX idx_audit_logs_user_created_at ON audit_logs (user_id, created_at DESC);

CREATE TABLE notifications (
    id          BIGSERIAL      PRIMARY KEY,
    public_id   UUID           NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    user_id     BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        VARCHAR(40)    NOT NULL,
    title       VARCHAR(150)   NOT NULL,
    message     VARCHAR(1000)  NOT NULL,
    -- frontend path to open, e.g. /admin/inventory/low-stock
    link        VARCHAR(255),
    channel     VARCHAR(20)    NOT NULL DEFAULT 'APP',
    is_read     BOOLEAN        NOT NULL DEFAULT FALSE,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_notifications_channel CHECK (channel IN ('APP', 'TELEGRAM', 'EMAIL'))
);

CREATE INDEX idx_notifications_user_unread ON notifications (user_id, is_read, created_at DESC);
