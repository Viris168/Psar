# Architecture

**Project:** Psar — online store + inventory + AI assistant (Cambodia)
**Style:** Modular monolith, event-driven background work
**Last updated:** 2026-11-24

See `PRD.md` for product requirements and `essential-architecture.md` for the one-page summary.

---

## 1. Tech stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Backend | Spring Boot 3, Spring Web, Spring Data JPA (Hibernate), Spring Security, Spring Validation |
| Build | Maven |
| Database | PostgreSQL + PGVector extension |
| Migrations | Flyway |
| Auth | JWT access token + refresh token (rotation) |
| Mapping / boilerplate | MapStruct, Lombok |
| API docs | SpringDoc OpenAPI (Swagger UI) |
| Messaging | RabbitMQ (Spring AMQP) — Spring events before Phase 9 |
| Scheduling | Spring `@Scheduled` + ShedLock |
| File storage | Cloudinary (separate root folders for dev and production) |
| AI | Spring AI + Google Gemini (chat + embeddings), Ollama for local testing |
| Frontend | React + TypeScript + Vite, Tailwind CSS + shadcn/ui, Axios + TanStack Query, Recharts |
| Maps | Leaflet + react-leaflet + OpenStreetMap, Nominatim reverse geocoding |
| Notifications | In-app, Telegram Bot API, email (later) |
| Payments | KHQR / Bakong (sandbox) |
| Testing | JUnit 5, Mockito, Spring Boot Test, Testcontainers |
| DevOps | Docker, Docker Compose, GitHub Actions |
| Hosting | Backend + DB on Render, frontend on Vercel |

---

## 2. System overview

```
                         ┌──────────────────────────┐
  Customer (browser) ───►│ React storefront (Vercel)│──┐
                         └──────────────────────────┘  │  HTTPS / JSON
  Staff/Manager/Admin ──►│ React admin panel        │──┤  JWT
                         └──────────────────────────┘  │
                                                       ▼
┌─────────────────────────────── Spring Boot monolith ───────────────────────────────┐
│                                                                                    │
│  REST controllers ─► services ─► repositories ─► PostgreSQL (+ PGVector)           │
│        │                  │                                                        │
│        │                  ├─► outbox_events ──(relay)──► RabbitMQ ──► @RabbitListener
│        │                  │                                    (same app)          │
│        │                  └─► Cloudinary (images, PDFs)                            │
│        │                                                                           │
│        ├─► SSE stream ◄── Spring AI ◄──► Gemini API                                │
│        │                                                                           │
│  Webhook ◄── KHQR / Bakong            @Scheduled jobs (ShedLock) ─► publish events │
│                                       Telegram Bot API ◄── notification consumer   │
└────────────────────────────────────────────────────────────────────────────────────┘
```

Everything runs as **one deployable Spring Boot app**. RabbitMQ is used inside the monolith: the app publishes and consumes its own messages. This gives durability, retries and a path to split modules later without changing business logic.

---

## 3. Modules

Each module owns its entities and exposes a service interface. Modules talk to each other through **services or events**, never through another module's repository.

| Module | Responsibility | Main tables |
|---|---|---|
| `user` | Auth, JWT, profiles, addresses | users, user_profiles, refresh_tokens, addresses |
| `delivery` | Delivery companies, fee calculation, driver assignment | delivery_companies |
| `catalog` | Categories, products, images, reviews | categories, products, product_images, reviews |
| `inventory` | Suppliers, purchase orders, stock movements, reservation | suppliers, purchase_orders, purchase_order_items, stock_movements |
| `order` | Cart, checkout, orders, status flow | carts, cart_items, orders, order_items, order_status_history |
| `payment` | KHQR, webhook, payment check, refunds | payments |
| `promotion` | Promotion rules, price calculation | promotions, promotion_targets, order_promotions |
| `loyalty` | Points, rewards, vouchers | point_rules, point_transactions, rewards, reward_redemptions |
| `subscription` | Plans, user subscriptions | subscription_plans, user_subscriptions |
| `notification` | In-app, Telegram, email | notifications |
| `audit` | Audit logging | audit_logs |
| `ai` | Chat, tools, memory, RAG | chat_conversations, chat_messages, ai_pending_actions, documents, vector_store |
| `messaging` | Outbox, RabbitMQ config, event types | outbox_events |
| `common` | Errors, security, base entities, utils | — |

### 3.1 Package layout

```
com.psar
├── common/            config, security, exception, web, util, BaseEntity
├── messaging/         RabbitConfig, OutboxEvent, OutboxRelay, event records
├── user/
│   ├── controller/    AuthController, ProfileController, AddressController
│   ├── service/
│   ├── repository/
│   ├── entity/
│   ├── dto/           request/ and response/
│   └── mapper/
├── delivery/  catalog/  inventory/  order/  payment/
├── promotion/ loyalty/  subscription/
├── notification/  audit/  ai/
└── PsarApplication.java
```

Same internal layout (`controller / service / repository / entity / dto / mapper`) in every module.

### 3.2 Frontend layout

```
frontend/
├── src/
│   ├── api/            axios client, endpoints per module
│   ├── features/       auth, catalog, cart, checkout, orders, loyalty, admin, chat
│   ├── components/     shared UI (shadcn/ui)
│   ├── hooks/
│   ├── routes/         storefront routes + /admin routes (role-guarded)
│   ├── i18n/           en.json, km.json
│   └── lib/
```

---

## 4. Data model

PostgreSQL. **36 application tables** + infrastructure tables (`flyway_schema_history`, `shedlock`).

### 4.1 Conventions

- `id BIGSERIAL` — internal primary key, never exposed.
- `public_id UUID UNIQUE NOT NULL DEFAULT gen_random_uuid()` — used in URLs/APIs for users, addresses, orders, payments, subscriptions, redemptions. Products and categories use `slug`.
- Money: `NUMERIC(12,2)` → `BigDecimal`.
- Enums: `VARCHAR` + `@Enumerated(EnumType.STRING)`.
- Timestamps: `created_at DEFAULT NOW()`, `updated_at` where rows change.
- Soft delete with `is_active` for products, categories, suppliers, companies, plans, rewards.
- Snapshots: orders copy address, product name, unit price, promotion names and company name at checkout.

### 4.2 Tables

**User & accounts**

| Table | Key columns |
|---|---|
| users | id, public_id, email (unique), password_hash, role (ADMIN/MANAGER/STAFF/CUSTOMER), is_active, email_verified, last_login_at |
| user_profiles | user_id (unique FK), full_name, full_name_kh, gender, date_of_birth, phone, avatar_url, position, telegram_chat_id, language, points_balance |
| refresh_tokens | user_id, token (unique), expires_at, revoked |
| addresses | public_id, user_id, label, receiver_name, phone, delivery_type (CITY/PROVINCE), province; CITY: latitude, longitude, map_address, district, commune, house_no, street, landmark; PROVINCE: delivery_company_id, courier_branch; note, is_default |

**Delivery**

| Table | Key columns |
|---|---|
| delivery_companies | name, logo_url, phone, tracking_url (template with `{tracking_no}`), base_fee, is_active |

**Catalog**

| Table | Key columns |
|---|---|
| categories | name, slug (unique), parent_id (self FK), image_url, is_active |
| products | sku (unique), name, slug (unique), description, category_id, supplier_id, cost_price, sell_price, quantity, min_stock, unit, is_active |
| product_images | product_id, image_url, is_main, sort_order |
| reviews | user_id, product_id, order_id, rating (1–5), comment — unique (user_id, product_id, order_id) |

**Inventory**

| Table | Key columns |
|---|---|
| suppliers | name, phone, email, address, is_active |
| stock_movements | product_id, type (IN/OUT/ADJUST), quantity, reason (PURCHASE/ORDER/RESERVE/RELEASE/CANCEL/DAMAGE/RETURN/REWARD), reference_id, created_by |
| purchase_orders | po_no (unique), supplier_id, status (PENDING/RECEIVED/CANCELLED), total_amount, created_by, received_at |
| purchase_order_items | purchase_order_id, product_id, quantity, unit_cost |

**Shopping & orders**

| Table | Key columns |
|---|---|
| carts | user_id (unique) |
| cart_items | cart_id, product_id, quantity > 0 — unique (cart_id, product_id) |
| orders | public_id, order_no, user_id, status, expires_at; delivery snapshot: delivery_type, receiver_name, receiver_phone, province, shipping_address, shipping_latitude, shipping_longitude, shipping_landmark, delivery_company_id, delivery_company_name, courier_branch, tracking_no, assigned_staff_id, assigned_at; money: subtotal, product_discount, order_discount, reward_discount, delivery_fee, total_amount; loyalty: reward_redemption_id, is_member_order, points_earned; note |
| order_items | order_id, product_id, product_name, quantity, free_quantity, unit_price, discount_amount, line_total |
| order_status_history | order_id, status, changed_by, note |
| payments | public_id, payment_type (ORDER/SUBSCRIPTION), order_id \| user_subscription_id (exactly one), method (KHQR/CASH_ON_DELIVERY), amount, currency (USD/KHR), transaction_ref (unique), qr_string, qr_md5, expires_at, raw_webhook (JSONB), status, paid_at, provider (BAKONG_KHQR; gateway), provider_ref (KHQR: qr md5), redirect_url, refund_required, refunded_at / _by, refund_reason, last_checked_at, check_attempts |
| payment_events | payment_id, source (CHECK_JOB/WEBHOOK/SIMULATED), provider, transaction_ref, provider_ref, amount, currency, payload (JSONB), signature_valid, outcome (APPLIED/DUPLICATE/LATE/AMOUNT_MISMATCH/UNKNOWN_PAYMENT/REJECTED) — append-only |

Order statuses: `PENDING_PAYMENT, CONFIRMED, PAID, PROCESSING, OUT_FOR_DELIVERY, HANDED_TO_COURIER, DELIVERED, CANCELLED, REFUNDED`.

**Promotions**

| Table | Key columns |
|---|---|
| promotions | name, description, banner_url, type (PERCENTAGE/FIXED_AMOUNT/BUY_X_GET_Y/FREE_DELIVERY), discount_value, max_discount, buy_quantity, get_quantity, apply_to (ALL/PRODUCTS/CATEGORIES/ORDER), min_order_amount, coupon_code (unique, null = automatic), members_only, usage_limit, per_user_limit, used_count, start_date, end_date, is_active, created_by |
| promotion_targets | promotion_id, product_id \| category_id (exactly one) |
| order_promotions | order_id, promotion_id, promotion_name, discount_amount |

**Points & rewards**

| Table | Key columns |
|---|---|
| point_rules | min_amount, points, member_points, is_active |
| point_transactions | user_id, type (EARN/REDEEM/EXPIRE/REFUND/ADJUST), points (±), order_id, redemption_id, note, expires_at |
| rewards | name, description, image_url, points_cost, reward_type (FREE_PRODUCT/DISCOUNT_VOUCHER/FREE_DELIVERY), product_id, discount_amount, stock, is_active |
| reward_redemptions | public_id, user_id, reward_id, points_used, voucher_code (unique), status (AVAILABLE/USED/EXPIRED/CANCELLED), used_order_id, expires_at |

**Subscription**

| Table | Key columns |
|---|---|
| subscription_plans | name, duration_months (1/12), price, description, free_city_delivery, is_active |
| user_subscriptions | public_id, user_id, plan_id, plan_name, price_paid, start_date, end_date, status (PENDING/ACTIVE/EXPIRED/CANCELLED), auto_renew |

**System**

| Table | Key columns |
|---|---|
| notifications | public_id, user_id, type, title, message, link, channel (APP/TELEGRAM/EMAIL), is_read, read_at |
| audit_logs | public_id, user_id, action (CREATE/UPDATE/DELETE/LOGIN/LOGIN_FAILED/AI_ACTION), entity, entity_id (public id), details (JSONB), ip_address — insert-only |
| outbox_events | id (UUID), aggregate_type, aggregate_id, event_type (routing key), payload (JSONB), status (PENDING/SENT/FAILED), attempts, created_at, sent_at |

**AI**

| Table | Key columns |
|---|---|
| chat_conversations | id (UUID), user_id, title |
| chat_messages | conversation_id, role (USER/ASSISTANT/TOOL), content, tool_name |
| ai_pending_actions | conversation_id, user_id, action_type, payload (JSONB), status (WAITING/CONFIRMED/REJECTED/EXPIRED) |
| documents | file_name, file_url, uploaded_by, status (PROCESSING/READY/FAILED) |
| vector_store | id (UUID), content, metadata (JSON), embedding VECTOR(768) — managed by Spring AI |

### 4.3 Key relationships

```
users 1─1 user_profiles, carts
users 1─N addresses, orders, reviews, user_subscriptions, point_transactions,
          reward_redemptions, notifications, chat_conversations
categories 1─N products, categories(sub)        suppliers 1─N products, purchase_orders
products 1─N product_images, reviews, cart_items, order_items, stock_movements,
             promotion_targets, rewards
orders 1─N order_items, order_status_history, payments, order_promotions
delivery_companies 1─N addresses, orders        subscription_plans 1─N user_subscriptions
promotions 1─N promotion_targets, order_promotions      rewards 1─N reward_redemptions
chat_conversations 1─N chat_messages, ai_pending_actions
```

### 4.4 Indexes

`products(slug)`, `products(category_id)`, `products(is_active)`, `orders(user_id)`, `orders(status)`, `orders(created_at)`, `orders(status, expires_at)`, `payments(status, created_at)`, `cart_items(cart_id)`, `stock_movements(product_id)`, `point_transactions(user_id)`, `promotions(is_active, start_date, end_date)`, `outbox_events(status, created_at)`, `chat_messages(conversation_id)`, `notifications(user_id, is_read)`.

### 4.5 Flyway migrations

Never edit an applied migration — always add a new version.

```
V1__users_and_accounts.sql      users, user_profiles, refresh_tokens, delivery_companies, addresses
V2__catalog_and_inventory.sql   categories, suppliers, products, product_images, stock_movements,
                                purchase_orders, purchase_order_items
V3__audit_and_notifications.sql audit_logs, notifications (Phase 3)
V4__orders_and_payments.sql     carts, cart_items, orders, order_items, order_status_history, payments
V5__payments_khqr.sql           payments: provider / provider_ref, refunds, check job fields; payment_events (Phase 5)
V6__promotions.sql              promotions, promotion_targets, order_promotions
V7__points_and_rewards.sql      point_rules, point_transactions, rewards, reward_redemptions
                                + ALTER orders ADD FK reward_redemption_id
V8__subscriptions.sql           subscription_plans, user_subscriptions
                                + ALTER payments ADD FK user_subscription_id
V9__messaging.sql               outbox_events (Phase 9; shedlock came with V4)
V10__ai_chatbot.sql             CREATE EXTENSION vector; chat_*, ai_pending_actions, documents
V11__reviews.sql                reviews
```

---

## 5. Core flows

### 5.1 Checkout (synchronous, one `@Transactional`)

```
POST /api/checkout
 1. Load cart; validate products active and quantities > 0
 2. Recalculate prices from DB (ignore any price sent by the client)
 3. PricingService: item promotions → coupon → reward voucher → delivery fee → total ≥ 0
 4. Validate delivery: CITY → pin in Phnom Penh; PROVINCE → company + branch, KHQR only
 5. For each item: UPDATE products SET quantity = quantity - :qty
                   WHERE id = :id AND quantity >= :qty      (0 rows → rollback, 409)
                   + stock_movements(OUT, RESERVE)
 6. Insert order (snapshots) + order_items + order_promotions + status history
      KHQR → PENDING_PAYMENT, expires_at = now + 15 min
      COD  → CONFIRMED
 7. Insert payment (PENDING) + generate KHQR (qr_string, qr_md5)
 8. Mark promotion usage / voucher USED; clear cart
 9. Write outbox event (order.created) in the same transaction
10. Return order public_id + QR
```

### 5.2 Payment

```
KHQR webhook ─► verify signature/amount ─► store raw_webhook ─► 200 OK
             ─► outbox: payment.received
consumer q.payment.process:
   if payment already SUCCESS → ignore (idempotent via transaction_ref)
   payment SUCCESS, order PAID, status history ─► outbox: order.paid

Safety net (every 1 min): PENDING payments older than 2 min → ask Bakong API by qr_md5
                           → if paid, process exactly like the webhook
```

### 5.3 Fulfilment

```
order.paid ─► q.delivery.assign
   CITY     → assign least-busy driver (or manual) → OUT_FOR_DELIVERY
   PROVINCE → staff hands parcel to company, enters tracking_no → HANDED_TO_COURIER
order.status.changed ─► q.notification (customer)
DELIVERED ─► order.delivered ─► q.points.award ─► points.earned ─► q.notification
```

### 5.4 Cancellation / expiry

```
Expire job (every 1 min): PENDING_PAYMENT AND expires_at < now
   → CANCELLED, stock back (IN, RELEASE), voucher/promotion usage released
   → order.expired ─► q.notification
Refund: payment REFUNDED, order REFUNDED, stock back, points REFUND (negative)
```

### 5.5 Pricing order (PricingService — pure, unit tested)

```
subtotal = Σ unit_price × quantity
− best automatic promotion per item (%, fixed, buy X get Y)
− one coupon / order promotion (respect min_order_amount, limits, members_only)
− one reward voucher
+ delivery fee (CITY: distance bands; free for active subscribers or FREE_DELIVERY promo;
                PROVINCE: company base_fee)
= total (never < 0)
points (at DELIVERED) = highest point_rule where (total − delivery_fee) > min_amount
                        → member_points if is_member_order else points
```

---

## 6. Messaging

### 6.1 Rules

- Publish only after commit — via the **transactional outbox** (`outbox_events` written in the business transaction; `OutboxRelay` publishes PENDING rows to RabbitMQ every few seconds and marks them SENT).
- Messages carry IDs, not full objects; consumers reload fresh data.
- Every consumer is **idempotent**.
- Retry 3× with backoff, then dead-letter queue. **Never** leave Spring's default infinite requeue.
- Durable quorum queues for payment-related queues.
- Before Phase 9: same event records published with Spring `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`.

### 6.2 Queue map

Exchange `shop.events` (topic), DLX `shop.dlx` → `q.dead-letter`.

| Routing key | Queue | Consumer job |
|---|---|---|
| payment.received | q.payment.process | Mark PAID, publish order.paid |
| order.paid | q.delivery.assign | Assign driver / courier task |
| order.paid | q.notification | Notify staff |
| order.status.changed | q.notification | Notify customer |
| order.expired | q.notification | "Your order was cancelled" |
| order.delivered | q.points.award | Award points |
| points.earned | q.notification | Notify customer |
| stock.low | q.notification | Notify manager |
| promotion.launched | q.promotion.broadcast | Send to all customers in batches |
| subscription.expiring | q.subscription | Reminder / expire |
| document.uploaded | q.ai.document | Split, embed, store in PGVector |

### 6.3 Scheduled jobs (ShedLock)

| Job | Schedule | Action |
|---|---|---|
| Outbox relay | every 5 s | Publish PENDING outbox events |
| Payment check | every 1 min | Ask Bakong about pending payments |
| Expire unpaid orders | every 1 min | Cancel + release stock |
| Promotion status | every 10 min | Activate/deactivate by date, publish promotion.launched |
| Low stock check | hourly | Publish stock.low (once per product per day) |
| Delivery overdue | hourly | Alert staff |
| Abandoned cart | hourly | Reminder after 24 h (once) |
| Subscription expiry | daily 00:05 | Reminders + expire |
| Voucher & points expiry | daily 00:10 | Expire + EXPIRE transactions |
| Token cleanup | daily 03:00 | Delete expired refresh tokens |
| Daily summary | daily 21:00 | Telegram summary to admin |

Jobs only find work and publish messages; consumers do the work.

---

## 7. Security

- Spring Security, stateless, JWT (short-lived access token, rotating refresh token stored in DB).
- `@PreAuthorize` on every admin/staff endpoint.
- Ownership check on every user-owned resource: `findByPublicIdAndUserId(...)` → 404 if not found (prevents IDOR).
- Separate customer and admin DTOs; never serialize entities. Never expose `id`, `cost_price`, `password_hash`.
- Bean Validation on all requests; global `@RestControllerAdvice` with a consistent error body.
- Passwords hashed with BCrypt.
- Webhook endpoint verifies the payment provider's data and amount before trusting it.
- CORS limited to the frontend origins.
- Secrets (DB, JWT, Gemini, Telegram, KHQR) only in environment variables.
- Audit log for sensitive actions and all AI actions.

---

## 8. AI chatbot

```
React chat ─SSE─► ChatController ─► Spring AI ChatClient
                                     ├─ system prompt (language, role, shop rules)
                                     ├─ ChatMemory (JDBC, per conversation)
                                     ├─ QuestionAnswerAdvisor (PGVector RAG)
                                     └─ Tools (@Tool) — chosen by user role
```

| Role | Tools |
|---|---|
| CUSTOMER | searchProducts, getMyOrders, getOrderStatus, getMyPoints, getMySubscription, getActivePromotions |
| MANAGER / ADMIN | + getLowStockProducts, getSalesSummary, proposePurchaseOrder |

- Tools call existing services with the **current authenticated user**; every tool re-checks role and ownership.
- Write actions create an `ai_pending_actions` row; the user confirms in the UI, then the normal service executes it and an `AI_ACTION` audit log is written.
- RAG ingestion: upload PDF → `documents` (PROCESSING) → `document.uploaded` → split + embed → `vector_store` → READY.
- API key only on the backend.

---

## 9. Deployment

### 9.1 Local (Docker Compose)

```
services: postgres (pgvector image), rabbitmq (management), backend, frontend
```

### 9.2 Production (portfolio)

| Part | Host |
|---|---|
| Frontend | Vercel |
| Backend | Render (Docker) |
| PostgreSQL | Render PostgreSQL (with pgvector) |
| RabbitMQ | Hosted RabbitMQ (free tier) or a container |
| Files | Cloudinary |

### 9.3 CI/CD (GitHub Actions)

Push / PR → build → unit + integration tests (Testcontainers) → build Docker image → deploy on `main`.

---

## 10. Scaling path

| Stage | When | Add |
|---|---|---|
| 1 | Now | One app + PostgreSQL + RabbitMQ in Docker |
| 2 | Real users | Redis cache (catalog, promotions), CDN for images |
| 3 | More traffic | 2+ backend instances behind a load balancer (stateless JWT, ShedLock already in place) |
| 4 | Heavy reads | PostgreSQL read replica for reports, full-text search |
| 5 | Large team | Split modules into services along existing event boundaries |

Already scale-safe: atomic stock updates, idempotent webhooks/consumers, files off the server disk, pagination, outbox, modular boundaries.

---

## 11. Architecture decisions (summary)

| # | Decision | Reason |
|---|---|---|
| 1 | Modular monolith | Simple to build and deploy alone; clear boundaries for later splitting |
| 2 | BIGINT internal id + UUID public_id | Fast joins; non-guessable public identifiers |
| 3 | Reserve stock at checkout, release on expiry | Prevent overselling and paying for unavailable items |
| 4 | Snapshots in orders | History never changes when products/addresses change |
| 5 | Rules in tables (promotions, points, plans, companies) | Change business rules without redeploying |
| 6 | Transactional outbox + RabbitMQ | No lost or phantom events; durable retries |
| 7 | Points awarded at DELIVERED | Avoid buy-then-cancel abuse |
| 8 | KHQR only for provinces | Avoid unpaid parcels via third-party couriers |
| 9 | AI actions require confirmation | Safe agent behaviour |
| 10 | Leaflet + OpenStreetMap | Free; Google Maps can replace it later |
