-- V1: users and accounts (Phase 1)
-- NEVER edit this file after it has run. Add V{n}__*.sql instead.

CREATE TABLE users (
    id              BIGSERIAL       PRIMARY KEY,
    public_id       UUID            NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    email           VARCHAR(150)    NOT NULL UNIQUE,
    password_hash   VARCHAR(255)    NOT NULL,
    role            VARCHAR(20)     NOT NULL
                    CHECK (role IN ('ADMIN', 'MANAGER', 'STAFF', 'CUSTOMER')),
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    email_verified  BOOLEAN         NOT NULL DEFAULT FALSE,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ
);

CREATE TABLE user_profiles (
    id                BIGSERIAL     PRIMARY KEY,
    user_id           BIGINT        NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    full_name         VARCHAR(100)  NOT NULL,
    full_name_kh      VARCHAR(100),
    gender            VARCHAR(10)   CHECK (gender IN ('MALE', 'FEMALE', 'OTHER')),
    date_of_birth     DATE,
    phone             VARCHAR(30),
    avatar_url        VARCHAR(255),
    position          VARCHAR(50),
    telegram_chat_id  VARCHAR(50),
    language          VARCHAR(5)    NOT NULL DEFAULT 'en' CHECK (language IN ('en', 'km')),
    points_balance    INT           NOT NULL DEFAULT 0 CHECK (points_balance >= 0),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ
);

CREATE TABLE refresh_tokens (
    id          BIGSERIAL     PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token       VARCHAR(255)  NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ   NOT NULL,
    revoked     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

CREATE TABLE delivery_companies (
    id            BIGSERIAL      PRIMARY KEY,
    name          VARCHAR(100)   NOT NULL,
    logo_url      VARCHAR(255),
    phone         VARCHAR(30),
    tracking_url  VARCHAR(255),
    base_fee      NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (base_fee >= 0),
    is_active     BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ
);

CREATE TABLE addresses (
    id                   BIGSERIAL     PRIMARY KEY,
    public_id            UUID          NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    user_id              BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label                VARCHAR(30),
    receiver_name        VARCHAR(100)  NOT NULL,
    phone                VARCHAR(30)   NOT NULL,
    delivery_type        VARCHAR(20)   NOT NULL CHECK (delivery_type IN ('CITY', 'PROVINCE')),
    province             VARCHAR(50)   NOT NULL,
    -- CITY (Phnom Penh)
    latitude             NUMERIC(9, 6),
    longitude            NUMERIC(9, 6),
    map_address          TEXT,
    district             VARCHAR(50),
    commune              VARCHAR(50),
    house_no             VARCHAR(30),
    street               VARCHAR(50),
    landmark             VARCHAR(150),
    -- PROVINCE
    delivery_company_id  BIGINT        REFERENCES delivery_companies (id),
    courier_branch       VARCHAR(150),
    note                 TEXT,
    is_default           BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ,
    CONSTRAINT chk_address_city CHECK (
        delivery_type <> 'CITY' OR (latitude IS NOT NULL AND longitude IS NOT NULL)
    ),
    CONSTRAINT chk_address_province CHECK (
        delivery_type <> 'PROVINCE' OR (delivery_company_id IS NOT NULL AND courier_branch IS NOT NULL)
    ),
    CONSTRAINT chk_address_in_cambodia CHECK (
        latitude IS NULL OR (latitude BETWEEN 10.0 AND 14.7 AND longitude BETWEEN 102.3 AND 107.7)
    )
);

CREATE INDEX idx_addresses_user_id ON addresses (user_id);
-- at most one default address per user
CREATE UNIQUE INDEX uq_addresses_default_per_user ON addresses (user_id) WHERE is_default;
