-- V2: catalog and inventory (Phase 2)
-- NEVER edit this file after it has run. Add V{n}__*.sql instead.

CREATE TABLE categories (
    id          BIGSERIAL     PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    name_kh     VARCHAR(100),
    slug        VARCHAR(120)  NOT NULL UNIQUE,
    -- NULL = top level; only two levels (enforced in CategoryService)
    parent_id   BIGINT        REFERENCES categories (id),
    image_url   VARCHAR(255),
    sort_order  INT           NOT NULL DEFAULT 0,
    is_active   BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ,
    CONSTRAINT chk_category_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX idx_categories_parent_id ON categories (parent_id);

CREATE TABLE suppliers (
    id          BIGSERIAL     PRIMARY KEY,
    public_id   UUID          NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name        VARCHAR(150)  NOT NULL,
    phone       VARCHAR(30),
    email       VARCHAR(150),
    address     TEXT,
    note        TEXT,
    is_active   BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ
);

CREATE TABLE products (
    id           BIGSERIAL       PRIMARY KEY,
    public_id    UUID            NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    sku          VARCHAR(50)     NOT NULL UNIQUE,
    name         VARCHAR(150)    NOT NULL,
    name_kh      VARCHAR(150),
    slug         VARCHAR(180)    NOT NULL UNIQUE,
    description  TEXT,
    category_id  BIGINT          NOT NULL REFERENCES categories (id),
    supplier_id  BIGINT          REFERENCES suppliers (id),
    cost_price   NUMERIC(12, 2)  NOT NULL DEFAULT 0 CHECK (cost_price >= 0),
    sell_price   NUMERIC(12, 2)  NOT NULL CHECK (sell_price > 0),
    -- changed only by atomic UPDATEs from inventory's StockService; never below zero
    quantity     INT             NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    min_stock    INT             NOT NULL DEFAULT 0 CHECK (min_stock >= 0),
    unit         VARCHAR(20)     NOT NULL DEFAULT 'pcs',
    is_active    BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ
);

CREATE INDEX idx_products_category_id ON products (category_id);
CREATE INDEX idx_products_supplier_id ON products (supplier_id);
CREATE INDEX idx_products_is_active ON products (is_active);

CREATE TABLE product_images (
    id           BIGSERIAL     PRIMARY KEY,
    public_id    UUID          NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    product_id   BIGINT        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    image_url    VARCHAR(500)  NOT NULL,
    storage_key  VARCHAR(255)  NOT NULL,
    is_main      BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order   INT           NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ
);

CREATE INDEX idx_product_images_product_id ON product_images (product_id);
-- at most one main image per product
CREATE UNIQUE INDEX uq_product_images_main_per_product ON product_images (product_id) WHERE is_main;

CREATE TABLE stock_movements (
    id             BIGSERIAL     PRIMARY KEY,
    product_id     BIGINT        NOT NULL REFERENCES products (id),
    type           VARCHAR(10)   NOT NULL CHECK (type IN ('IN', 'OUT', 'ADJUST')),
    -- signed change: positive adds stock, negative removes it
    quantity       INT           NOT NULL CHECK (quantity <> 0),
    balance_after  INT           NOT NULL CHECK (balance_after >= 0),
    reason         VARCHAR(20)   NOT NULL CHECK (reason IN ('PURCHASE', 'ORDER', 'RESERVE', 'RELEASE', 'CANCEL',
                                                            'DAMAGE', 'RETURN', 'CORRECTION', 'REWARD')),
    -- id of the purchase order / order that caused the movement, if any
    reference_id   BIGINT,
    note           VARCHAR(255),
    created_by     BIGINT        REFERENCES users (id),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_stock_movement_direction CHECK (
        (type = 'IN' AND quantity > 0) OR (type = 'OUT' AND quantity < 0) OR type = 'ADJUST'
    )
);

CREATE INDEX idx_stock_movements_product_created ON stock_movements (product_id, created_at);

CREATE SEQUENCE po_no_seq;

CREATE TABLE purchase_orders (
    id            BIGSERIAL       PRIMARY KEY,
    po_no         VARCHAR(20)     NOT NULL UNIQUE DEFAULT ('PO-' || LPAD(nextval('po_no_seq')::TEXT, 6, '0')),
    supplier_id   BIGINT          NOT NULL REFERENCES suppliers (id),
    status        VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'RECEIVED', 'CANCELLED')),
    total_amount  NUMERIC(12, 2)  NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    note          TEXT,
    created_by    BIGINT          REFERENCES users (id),
    received_at   TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ
);

CREATE INDEX idx_purchase_orders_status ON purchase_orders (status);
CREATE INDEX idx_purchase_orders_supplier_id ON purchase_orders (supplier_id);

CREATE TABLE purchase_order_items (
    id                 BIGSERIAL       PRIMARY KEY,
    purchase_order_id  BIGINT          NOT NULL REFERENCES purchase_orders (id) ON DELETE CASCADE,
    product_id         BIGINT          NOT NULL REFERENCES products (id),
    quantity           INT             NOT NULL CHECK (quantity > 0),
    unit_cost          NUMERIC(12, 2)  NOT NULL CHECK (unit_cost >= 0),
    CONSTRAINT uq_purchase_order_items_product UNIQUE (purchase_order_id, product_id)
);
