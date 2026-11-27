-- V4: carts, orders and payments (Phase 4), plus the ShedLock table
-- NEVER edit this file after it has run. Add V{n}__*.sql instead.
-- Promotion, reward, points and subscription links are added by their phases' migrations.

CREATE TABLE carts (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ
);

CREATE TABLE cart_items (
    id          BIGSERIAL    PRIMARY KEY,
    cart_id     BIGINT       NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    product_id  BIGINT       NOT NULL REFERENCES products (id),
    quantity    INT          NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ,
    CONSTRAINT uq_cart_items_product UNIQUE (cart_id, product_id)
);

CREATE SEQUENCE order_no_seq;

CREATE TABLE orders (
    id                     BIGSERIAL      PRIMARY KEY,
    public_id              UUID           NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    order_no               VARCHAR(20)    NOT NULL UNIQUE DEFAULT ('ORD-' || LPAD(nextval('order_no_seq')::TEXT, 6, '0')),
    user_id                BIGINT         NOT NULL REFERENCES users (id),
    status                 VARCHAR(30)    NOT NULL,
    payment_method         VARCHAR(20)    NOT NULL,
    -- KHQR: pay before this or the order is cancelled; NULL for COD
    expires_at             TIMESTAMPTZ,
    -- delivery snapshot (copied at checkout, never follows later edits)
    delivery_type          VARCHAR(20)    NOT NULL,
    receiver_name          VARCHAR(100)   NOT NULL,
    receiver_phone         VARCHAR(30)    NOT NULL,
    province               VARCHAR(100)   NOT NULL,
    shipping_address       TEXT           NOT NULL,
    shipping_latitude      NUMERIC(9, 6),
    shipping_longitude     NUMERIC(9, 6),
    shipping_landmark      VARCHAR(255),
    delivery_company_id    BIGINT         REFERENCES delivery_companies (id),
    delivery_company_name  VARCHAR(100),
    courier_branch         VARCHAR(150),
    tracking_no            VARCHAR(100),
    assigned_staff_id      BIGINT         REFERENCES users (id),
    assigned_at            TIMESTAMPTZ,
    -- money
    subtotal               NUMERIC(12, 2) NOT NULL,
    product_discount       NUMERIC(12, 2) NOT NULL DEFAULT 0,
    order_discount         NUMERIC(12, 2) NOT NULL DEFAULT 0,
    reward_discount        NUMERIC(12, 2) NOT NULL DEFAULT 0,
    delivery_fee           NUMERIC(12, 2) NOT NULL,
    total_amount           NUMERIC(12, 2) NOT NULL,
    note                   VARCHAR(500),
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ,
    CONSTRAINT chk_orders_total_not_negative CHECK (total_amount >= 0),
    CONSTRAINT chk_orders_status CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'PAID', 'PROCESSING',
        'OUT_FOR_DELIVERY', 'HANDED_TO_COURIER', 'DELIVERED', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT chk_orders_payment_method CHECK (payment_method IN ('KHQR', 'CASH_ON_DELIVERY')),
    CONSTRAINT chk_orders_delivery_type CHECK (delivery_type IN ('CITY', 'PROVINCE'))
);

CREATE INDEX idx_orders_user_created_at ON orders (user_id, created_at DESC);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_status_expires_at ON orders (status, expires_at);
CREATE INDEX idx_orders_created_at ON orders (created_at);
CREATE INDEX idx_orders_assigned_staff_id ON orders (assigned_staff_id);

CREATE TABLE order_items (
    id               BIGSERIAL      PRIMARY KEY,
    order_id         BIGINT         NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id       BIGINT         NOT NULL REFERENCES products (id),
    product_name     VARCHAR(150)   NOT NULL,
    sku              VARCHAR(50)    NOT NULL,
    quantity         INT            NOT NULL CHECK (quantity > 0),
    free_quantity    INT            NOT NULL DEFAULT 0 CHECK (free_quantity >= 0),
    unit_price       NUMERIC(12, 2) NOT NULL,
    discount_amount  NUMERIC(12, 2) NOT NULL DEFAULT 0,
    line_total       NUMERIC(12, 2) NOT NULL,
    CONSTRAINT uq_order_items_product UNIQUE (order_id, product_id)
);

CREATE TABLE order_status_history (
    id          BIGSERIAL    PRIMARY KEY,
    order_id    BIGINT       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    status      VARCHAR(30)  NOT NULL,
    -- NULL = system (e.g. the expiry job)
    changed_by  BIGINT       REFERENCES users (id),
    note        VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_status_history_order_id ON order_status_history (order_id);

CREATE TABLE payments (
    id               BIGSERIAL      PRIMARY KEY,
    public_id        UUID           NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    payment_type     VARCHAR(20)    NOT NULL DEFAULT 'ORDER',
    order_id         BIGINT         REFERENCES orders (id),
    method           VARCHAR(20)    NOT NULL,
    amount           NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    currency         VARCHAR(3)     NOT NULL DEFAULT 'USD',
    status           VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    transaction_ref  VARCHAR(100)   UNIQUE,
    qr_string        TEXT,
    qr_md5           VARCHAR(64),
    expires_at       TIMESTAMPTZ,
    raw_webhook      JSONB,
    paid_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ,
    CONSTRAINT chk_payments_type CHECK (payment_type IN ('ORDER', 'SUBSCRIPTION')),
    -- Phase 8 adds user_subscription_id and widens this to "exactly one of the two"
    CONSTRAINT chk_payments_order CHECK (payment_type <> 'ORDER' OR order_id IS NOT NULL),
    CONSTRAINT chk_payments_method CHECK (method IN ('KHQR', 'CASH_ON_DELIVERY')),
    CONSTRAINT chk_payments_currency CHECK (currency IN ('USD', 'KHR')),
    CONSTRAINT chk_payments_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'CANCELLED', 'REFUNDED'))
);

CREATE INDEX idx_payments_order_id ON payments (order_id);
CREATE INDEX idx_payments_status_created_at ON payments (status, created_at);

-- ShedLock (JdbcTemplateLockProvider): one instance runs each @Scheduled job at a time
CREATE TABLE shedlock (
    name        VARCHAR(64)   NOT NULL PRIMARY KEY,
    lock_until  TIMESTAMPTZ   NOT NULL,
    locked_at   TIMESTAMPTZ   NOT NULL,
    locked_by   VARCHAR(255)  NOT NULL
);
