# Essential Architecture (one-page summary)

The must-know decisions for Psar. Full details in `architecture.md`.

## What it is

One **Spring Boot modular monolith** + **React** frontend + **PostgreSQL** + **RabbitMQ**, deployed with Docker.
An online store for Cambodia with inventory, promotions, points, subscriptions and an AI chatbot.

```
React (storefront + admin) ──JWT──► Spring Boot monolith ──► PostgreSQL (+PGVector)
                                        │   ├──► outbox ─► RabbitMQ ─► consumers (same app)
                                        │   ├──► MinIO / S3
                                        │   └──► Spring AI ─► Gemini
                          KHQR webhook ─┘   @Scheduled jobs (ShedLock)
```

## Stack

Java 21 · Spring Boot 3 · Maven · PostgreSQL · Flyway · Spring Security + JWT · MapStruct · Lombok · SpringDoc · RabbitMQ · ShedLock · Spring AI + Gemini · PGVector · MinIO/S3 · React + TS + Vite · Tailwind + shadcn/ui · TanStack Query · Leaflet · JUnit 5 + Mockito + Testcontainers · Docker Compose · GitHub Actions · Render + Vercel

## Modules

`user` · `delivery` · `catalog` · `inventory` · `order` · `payment` · `promotion` · `loyalty` · `subscription` · `notification` · `audit` · `ai` · `messaging` · `common`

Modules call each other through **services or events**, never another module's repository.

## Roles

`ADMIN` (everything) · `MANAGER` (products, stock, promotions, reports) · `STAFF` (process orders, drivers) · `CUSTOMER` (shop, pay, points).

## The 12 rules

1. **Money** is `NUMERIC(12,2)` / `BigDecimal`. Never `float`/`double`.
2. **IDs:** internal `BIGSERIAL id`; APIs use `public_id UUID` (or `slug` for products/categories).
3. **Ownership:** every user resource is loaded with `findByPublicIdAndUserId` — no IDOR.
4. **DTOs only.** Never return entities. Never expose `id`, `cost_price`, `password_hash`.
5. **Prices are recalculated on the backend.** Never trust the client.
6. **Checkout is one transaction** and **reserves stock atomically**:
   `UPDATE products SET quantity = quantity - ? WHERE id = ? AND quantity >= ?`
7. **Every stock change** writes a `stock_movements` row in the same transaction.
8. **Orders snapshot** address, product names, prices, promotions and company name.
9. **Unpaid KHQR orders expire after 15 min** and release stock.
10. **Events go through the outbox**, consumers are **idempotent**, retry 3× then DLQ.
11. **Flyway migrations are never edited** after they run — add a new version.
12. **AI tools respect roles and ownership**; AI write actions need user confirmation.

## Delivery

| CITY (Phnom Penh) | PROVINCE |
|---|---|
| Map pin, own driver | Customer picks delivery company + branch |
| KHQR or COD | KHQR only |
| Fee by distance, free for subscribers | Company base fee |
| `OUT_FOR_DELIVERY` | `HANDED_TO_COURIER` + tracking no. |

## Order status

```
KHQR: PENDING_PAYMENT → PAID → PROCESSING → OUT_FOR_DELIVERY | HANDED_TO_COURIER → DELIVERED
COD:  CONFIRMED → PROCESSING → OUT_FOR_DELIVERY → DELIVERED
→ CANCELLED (before delivery)   → REFUNDED (after)
```

## Pricing order

`subtotal → best item promotion → one coupon → one reward voucher → delivery fee → total (≥ 0)`
Points at **DELIVERED**: highest `point_rule` on (total − delivery fee); member points for subscribers.

## Sync vs async

| Synchronous | Queue (event) | Scheduled |
|---|---|---|
| Auth, cart, catalog, checkout, create order + QR, admin CRUD, AI chat (SSE) | Payment processing, delivery assignment, notifications, points, promotion broadcast, subscription reminders, RAG ingestion | Outbox relay, payment check, expire unpaid orders, promotion on/off, low stock, overdue delivery, abandoned cart, expiries, token cleanup, daily summary |

## Data model at a glance (36 tables)

| Area | Tables |
|---|---|
| Users (4) | users, user_profiles, refresh_tokens, addresses |
| Delivery (1) | delivery_companies |
| Catalog (4) | categories, products, product_images, reviews |
| Inventory (4) | suppliers, stock_movements, purchase_orders, purchase_order_items |
| Orders (6) | carts, cart_items, orders, order_items, order_status_history, payments |
| Promotions (3) | promotions, promotion_targets, order_promotions |
| Loyalty (4) | point_rules, point_transactions, rewards, reward_redemptions |
| Subscription (2) | subscription_plans, user_subscriptions |
| System (3) | notifications, audit_logs, outbox_events |
| AI (5) | chat_conversations, chat_messages, ai_pending_actions, documents, vector_store |

## Build order

Setup → Users → Catalog & inventory → Audit/notifications → Orders & checkout → **Payments (MVP, week 13)** → Promotions → Points → Subscription → RabbitMQ & jobs → Dashboard → AI chatbot → Tests → **Launch (week 26)**
