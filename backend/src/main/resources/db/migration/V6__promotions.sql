-- V6: promotions, coupons and the promotions each order used (Phase 6)
-- NEVER edit this file after it has run. Add V{n}__*.sql instead.

-- A promotion is automatic (coupon_code NULL) or needs its coupon code. used_count is only ever changed by the
-- atomic UPDATE in PromotionUsageService (never through JPA), so a usage limit holds under concurrent checkouts.
CREATE TABLE promotions (
    id                  BIGSERIAL      PRIMARY KEY,
    public_id           UUID           NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name                VARCHAR(150)   NOT NULL,
    description         TEXT,
    banner_url          VARCHAR(500),
    type                VARCHAR(20)    NOT NULL,
    discount_value      NUMERIC(12, 2),
    max_discount        NUMERIC(12, 2),
    buy_quantity        INT,
    get_quantity        INT,
    apply_to            VARCHAR(20)    NOT NULL,
    min_order_amount    NUMERIC(12, 2),
    -- stored upper-case; customers may type it in any case
    coupon_code         VARCHAR(30)    UNIQUE,
    members_only        BOOLEAN        NOT NULL DEFAULT FALSE,
    usage_limit         INT,
    per_user_limit      INT,
    used_count          INT            NOT NULL DEFAULT 0,
    start_date          TIMESTAMPTZ    NOT NULL,
    end_date            TIMESTAMPTZ    NOT NULL,
    is_active           BOOLEAN        NOT NULL DEFAULT TRUE,
    -- announce it to every customer when it starts (PromotionLaunchJob); broadcast_at = when that happened
    broadcast_on_start  BOOLEAN        NOT NULL DEFAULT FALSE,
    broadcast_at        TIMESTAMPTZ,
    created_by          BIGINT         NOT NULL REFERENCES users (id),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,
    CONSTRAINT chk_promotions_type CHECK (type IN ('PERCENTAGE', 'FIXED_AMOUNT', 'BUY_X_GET_Y', 'FREE_DELIVERY')),
    CONSTRAINT chk_promotions_apply_to CHECK (apply_to IN ('ALL', 'PRODUCTS', 'CATEGORIES', 'ORDER')),
    CONSTRAINT chk_promotions_percentage CHECK (type <> 'PERCENTAGE'
        OR (discount_value > 0 AND discount_value <= 100)),
    CONSTRAINT chk_promotions_fixed CHECK (type <> 'FIXED_AMOUNT' OR discount_value > 0),
    CONSTRAINT chk_promotions_value_needed CHECK (type IN ('BUY_X_GET_Y', 'FREE_DELIVERY')
        OR discount_value IS NOT NULL),
    CONSTRAINT chk_promotions_bxgy CHECK (type <> 'BUY_X_GET_Y'
        OR (COALESCE(buy_quantity, 0) >= 1 AND COALESCE(get_quantity, 0) >= 1 AND apply_to <> 'ORDER')),
    CONSTRAINT chk_promotions_bxgy_only CHECK (type = 'BUY_X_GET_Y'
        OR (buy_quantity IS NULL AND get_quantity IS NULL)),
    CONSTRAINT chk_promotions_free_delivery CHECK (type <> 'FREE_DELIVERY' OR apply_to = 'ORDER'),
    CONSTRAINT chk_promotions_max_discount CHECK (max_discount IS NULL OR max_discount > 0),
    CONSTRAINT chk_promotions_min_order CHECK (min_order_amount IS NULL OR min_order_amount >= 0),
    CONSTRAINT chk_promotions_coupon_code CHECK (coupon_code IS NULL OR coupon_code ~ '^[A-Z0-9_-]{3,30}$'),
    CONSTRAINT chk_promotions_usage_limit CHECK (usage_limit IS NULL OR usage_limit >= 1),
    CONSTRAINT chk_promotions_per_user_limit CHECK (per_user_limit IS NULL OR per_user_limit >= 1),
    CONSTRAINT chk_promotions_used_count CHECK (used_count >= 0
        AND (usage_limit IS NULL OR used_count <= usage_limit)),
    CONSTRAINT chk_promotions_dates CHECK (end_date > start_date)
);

CREATE INDEX idx_promotions_active_dates ON promotions (is_active, start_date, end_date);

-- What an item promotion (apply_to PRODUCTS / CATEGORIES) applies to: one product or one category per row.
-- A top-level category also covers its subcategories.
CREATE TABLE promotion_targets (
    id            BIGSERIAL  PRIMARY KEY,
    promotion_id  BIGINT     NOT NULL REFERENCES promotions (id) ON DELETE CASCADE,
    product_id    BIGINT     REFERENCES products (id),
    category_id   BIGINT     REFERENCES categories (id),
    CONSTRAINT chk_promotion_targets_one CHECK ((product_id IS NULL) <> (category_id IS NULL)),
    CONSTRAINT uq_promotion_targets_product UNIQUE (promotion_id, product_id),
    CONSTRAINT uq_promotion_targets_category UNIQUE (promotion_id, category_id)
);

CREATE INDEX idx_promotion_targets_product_id ON promotion_targets (product_id);
CREATE INDEX idx_promotion_targets_category_id ON promotion_targets (category_id);

-- Snapshot of every promotion an order used, and how much it took off. released_at: the order was cancelled,
-- expired or refunded and the use was given back (it no longer counts against usage or per-user limits).
CREATE TABLE order_promotions (
    id               BIGSERIAL      PRIMARY KEY,
    order_id         BIGINT         NOT NULL REFERENCES orders (id),
    promotion_id     BIGINT         NOT NULL REFERENCES promotions (id),
    promotion_name   VARCHAR(150)   NOT NULL,
    coupon_code      VARCHAR(30),
    level            VARCHAR(10)    NOT NULL,
    discount_amount  NUMERIC(12, 2) NOT NULL CHECK (discount_amount >= 0),
    user_id          BIGINT         NOT NULL REFERENCES users (id),
    released_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_order_promotions_level CHECK (level IN ('ITEM', 'ORDER')),
    CONSTRAINT uq_order_promotions UNIQUE (order_id, promotion_id)
);

-- per-user limits count this user's uses that were not given back
CREATE INDEX idx_order_promotions_promotion_user ON order_promotions (promotion_id, user_id)
    WHERE released_at IS NULL;

-- Buy X get Y: the free units are part of quantity (buy 2 get 1 = quantity 3, free 1), never extra units.
ALTER TABLE order_items
    ADD CONSTRAINT chk_order_items_free_quantity CHECK (free_quantity <= quantity);
