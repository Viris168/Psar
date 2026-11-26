# Product Requirements Document (PRD)

**Project:** Psar (placeholder name — "market" in Khmer)
**Type:** Online store + inventory management + AI shopping assistant
**Market:** Cambodia
**Owner:** Solo developer (portfolio / internship project)
**Status:** Planning — v1.0
**Last updated:** 2026-11-24

---

## 1. Overview

Psar is an online store for a Cambodian shop. Customers browse products, add them to a cart, pay with KHQR or cash on delivery, and track their orders. The shop delivers inside Phnom Penh with its own drivers; province orders are sent through a delivery company chosen by the customer.

Behind the store is a full inventory system (suppliers, purchase orders, stock history), a loyalty program (points, rewards, subscriptions), a promotion engine, and an AI chatbot that answers customers and helps staff.

### 1.1 Goals

| Goal | Measure |
|---|---|
| Customers can buy online end-to-end | Order → KHQR payment → delivery → points works on the live demo |
| Stock is always correct | No overselling, even under concurrent purchases (tested) |
| Fits Cambodian reality | Map pin delivery in Phnom Penh, courier delivery for provinces, KHQR, Khmer + English |
| Demonstrates professional engineering | Clean modular monolith, tests, CI/CD, Docker, event-driven background work |
| Portfolio-ready | Live demo, demo accounts, README, 3-minute video |

### 1.2 Non-goals (v1)

- Native mobile apps (web is responsive instead)
- Multi-vendor marketplace (one shop only)
- Microservices / Kubernetes
- Real card payments (KHQR sandbox + COD only)
- Live driver GPS tracking

---

## 2. Users and roles

| Role | Who | Main goals |
|---|---|---|
| **CUSTOMER** | Public shoppers | Find products, buy, pay, track orders, earn and use points |
| **STAFF** | Packers, drivers | Process orders, deliver, enter courier tracking numbers |
| **MANAGER** | Store manager | Products, stock, suppliers, purchase orders, promotions, reports |
| **ADMIN** | Owner | Everything + users, delivery companies, plans, rewards, AI documents, audit logs |

Drivers are STAFF users with `position = "Driver"`.

### 2.1 Permission matrix

| Feature | ADMIN | MANAGER | STAFF | CUSTOMER |
|---|:-:|:-:|:-:|:-:|
| Manage users | ✅ | ❌ | ❌ | ❌ |
| Products / categories / suppliers | ✅ | ✅ | view | view (public data only) |
| Purchase orders, stock adjustment | ✅ | ✅ | ❌ | ❌ |
| Process orders (status, assign, tracking) | ✅ | ✅ | ✅ | ❌ |
| Place orders, cart, addresses | — | — | — | ✅ |
| Promotions | ✅ | ✅ | ❌ | view |
| Rewards, point rules, subscription plans | ✅ | ❌ | ❌ | view / use |
| Delivery companies | ✅ | ❌ | ❌ | view |
| Dashboard & reports | ✅ | ✅ | ❌ | ❌ |
| Upload AI documents | ✅ | ❌ | ❌ | ❌ |
| Chatbot — questions | ✅ | ✅ | ✅ | ✅ (own data only) |
| Chatbot — actions (e.g. create PO) | ✅ | ✅ | ❌ | ❌ |
| Audit logs | ✅ | ❌ | ❌ | ❌ |

---

## 3. Features and requirements

Priority: **P0** = MVP, **P1** = full v1, **P2** = nice to have.

### 3.1 Accounts (P0)

- Register with email + password; login returns JWT access token + refresh token.
- Refresh token rotation; logout revokes the refresh token.
- Profile: full name (English + Khmer), gender, date of birth, phone, avatar, language (`en` / `km`), Telegram chat ID.
- Change password.
- Customers manage multiple addresses with one default.

### 3.2 Addresses and delivery (P0)

Two delivery types:

| | CITY | PROVINCE |
|---|---|---|
| Area | Phnom Penh | All other provinces |
| Delivered by | Shop's own drivers | Delivery company chosen by customer |
| Location input | **Map pin required** (Leaflet + OpenStreetMap, "use my location"), plus house no., street, landmark | Province + delivery company + branch name |
| Payment | KHQR or Cash on Delivery | **KHQR only** |
| Fee | By distance from shop (e.g. < 5 km $1, 5–15 km $2) | Company's fixed base fee |
| Tracking | Order status timeline | Company tracking number + tracking link |

- The pin must be inside Cambodia (lat ≈ 10–14.7, lng ≈ 102.3–107.7); CITY pins must be in Phnom Penh.
- Admin manages the list of delivery companies (name, logo, phone, tracking URL template, base fee, active).

### 3.3 Catalog (P0)

- Categories with optional subcategories and URL slugs.
- Products: SKU, name, slug, description, category, supplier, cost price (hidden from customers), sell price, stock quantity, minimum stock level, unit, multiple images (one main).
- Storefront: home page, category pages, product page, search, filters, pagination.
- Reviews (P2): only customers who received the product can rate 1–5 and comment.

### 3.4 Inventory (P0)

- Suppliers CRUD.
- Purchase orders to suppliers: create → receive (stock IN) → or cancel.
- Manual stock adjustments with a reason (damage, return, correction).
- Every stock change is written to the stock movement history.
- Low-stock alerts when quantity ≤ minimum stock.

### 3.5 Cart and checkout (P0)

- Customers add, update and remove cart items.
- Checkout runs as **one database transaction**:
  1. Validate cart (products active, quantities > 0)
  2. Recalculate all prices on the backend
  3. Apply promotions → coupon → reward voucher
  4. Validate delivery type and calculate the delivery fee
  5. **Reserve stock atomically** (fails with a clear message if not enough)
  6. Create the order with a snapshot of address, product names, prices and promotions
  7. Create the payment and KHQR code (KHQR) or confirm directly (COD)
  8. Clear the cart
- KHQR orders must be paid within **15 minutes**, otherwise they are cancelled and stock is released.

### 3.6 Orders (P0)

Status flow:

```
KHQR:  PENDING_PAYMENT → PAID → PROCESSING → OUT_FOR_DELIVERY  → DELIVERED   (CITY)
                                           → HANDED_TO_COURIER → DELIVERED   (PROVINCE)
COD:   CONFIRMED → PROCESSING → OUT_FOR_DELIVERY → DELIVERED (paid on delivery)
Any pre-delivery state → CANCELLED (stock released) ; DELIVERED → REFUNDED
```

- Every status change is recorded with who changed it and when (timeline for the customer).
- CITY orders are assigned to a driver (manual in MVP, auto least-busy driver in P1).
- PROVINCE orders: staff enters the tracking number when handing the parcel to the company.
- Customers see only their own orders.

### 3.7 Payments (P0)

- KHQR (sandbox) QR generation per order.
- Payment webhook: verify, store raw payload, process **idempotently** (unique transaction reference).
- Payment status check job catches missed webhooks.
- Cash on Delivery for CITY orders only.
- Refunds mark the payment REFUNDED and release stock / reverse points.

### 3.8 Promotions (P1)

| Type | Example |
|---|---|
| PERCENTAGE | 50%, 20%, 70% off (optional max discount cap) |
| FIXED_AMOUNT | $5 off |
| BUY_X_GET_Y | Buy 2 get 1 free (cheapest item free) |
| FREE_DELIVERY | Free delivery for orders over $30 |

- Target: all products, specific products, categories, or the whole order.
- Options: coupon code or automatic, members only, minimum order amount, total usage limit, per-user limit, start/end dates.
- Stacking: best **one** automatic promotion per item + **one** coupon per order + **one** reward voucher per order. Total never below $0.
- Promotions turn on/off automatically by date; admins can broadcast a promotion to all customers.

### 3.9 Points and rewards (P1)

| Order amount (paid for products, excl. delivery) | Points | Subscriber points |
|---|---|---|
| > $50 | 1 | 2 |
| > $100 | 5 | 5 |

- Only the highest matching rule applies. Rules are editable by admin.
- Points are awarded only when the order is **DELIVERED**; reversed if refunded.
- Points can expire (e.g. 1 year).
- Rewards catalog: free product, discount voucher, free delivery. Each has a points cost and optional stock.
- Redeeming creates a voucher code the customer applies at checkout.

### 3.10 Subscription (P1)

| Plan | Duration | Benefits |
|---|---|---|
| Monthly | 1 month | Free city delivery, member points, members-only promotions |
| Yearly | 12 months | Same, lower price per month |

- Paid with KHQR. One active subscription per user; early renewal extends the end date.
- Reminder 3 days before expiry; automatic expiry.

### 3.11 Notifications (P0 in-app, P1 Telegram)

| Event | Who is notified |
|---|---|
| New paid order | Staff |
| Order status changed / tracking number added | Customer |
| Order cancelled (unpaid) | Customer |
| Low stock | Manager |
| Points earned | Customer |
| Subscription expiring | Customer |
| Promotion launched | All customers |
| Daily summary | Admin |

Channels: in-app (P0), Telegram (P1), email (P2).

### 3.12 Dashboard and reports (P1)

- Sales per day/month, revenue, top products, low stock list, orders by province and delivery type.
- Export sales report to Excel (P2, background job).

### 3.13 AI chatbot (P1)

- Chat window in the storefront and admin panel; replies stream word by word.
- Replies in Khmer or English based on the user's language.
- Conversation history is saved.
- Tools by role:
  - CUSTOMER: search products, "my orders", "my points", "my subscription", active promotions, store policies.
  - MANAGER/ADMIN: stock report, sales summary, create purchase order (**requires confirmation**).
- RAG: admin uploads PDFs (return policy, FAQ, product manuals); the bot answers from them.
- The bot can never access another user's data or perform actions above the user's role.

### 3.14 Audit logs (P0)

- Log create/update/delete of important records, logins, and AI actions with user, entity and details.

---

## 4. Non-functional requirements

| Area | Requirement |
|---|---|
| Correctness | No overselling: concurrent test (10 buyers, 3 items left → exactly 3 succeed) |
| Security | JWT + role checks on every endpoint; ownership checks on every user resource; public UUIDs in APIs; secrets only in environment variables; cost price and password hash never returned |
| Reliability | Background work survives restarts (RabbitMQ durable queues), retries ×3, dead-letter queue, idempotent consumers |
| Performance | Product list and product page < 500 ms on the demo server; all lists paginated |
| Money | Stored as `NUMERIC(12,2)`, handled as `BigDecimal` |
| Localization | English and Khmer UI text; USD primary, KHR supported for payments |
| Usability | Mobile-first responsive UI |
| Maintainability | Modular monolith; Flyway migrations; Swagger docs; CI on every push |
| Testing | Unit tests for checkout, promotions, points, stock; integration tests for main APIs |

---

## 5. Milestones

Assumes ~20 hours/week, start **2026-11-30**. Full estimate ≈ 440 h (~525 h with buffer).

| Week | Milestone | Scope |
|---|---|---|
| 1 | Setup | Repos, Docker Compose, CI, first deploy |
| 3 | Accounts | Auth, roles, profile, addresses with map, delivery companies |
| 6 | Catalog & inventory | Products, images, suppliers, purchase orders, stock history |
| 7 | System | Audit logs, in-app notifications |
| 11 | Shopping | Storefront, cart, checkout with stock reservation, orders |
| **13** | **MVP live** | **KHQR payments, webhook, payment check, COD → start applying for internships** |
| 15 | Promotions | All promotion types, coupons, stacking |
| 17 | Points & rewards | Point rules, ledger, rewards, vouchers |
| 18 | Subscription | Plans, payment, benefits, expiry |
| 20 | Event-driven | RabbitMQ, outbox, background jobs, Telegram, ShedLock |
| 21 | Dashboard | Charts, reports |
| 24 | AI chatbot | Spring AI, tools, memory, RAG |
| 25 | Testing | Coverage of critical logic, concurrency test |
| **26** | **v1.0 launch** | **Final deploy, README, demo video** |

**Cut order if late:** reviews → Excel export → abandoned-cart reminder → auto driver assignment → RabbitMQ (keep Spring events). Never cut: auth, checkout/stock, payments, chatbot.

---

## 6. Success criteria (v1.0)

- [ ] Live URL with demo accounts for all 4 roles
- [ ] A customer can buy with KHQR (sandbox) and COD, in city and province
- [ ] Stock, promotions and points are correct and covered by tests
- [ ] Background jobs and queues visible in RabbitMQ management UI
- [ ] Chatbot answers in Khmer and English, uses tools, and respects roles
- [ ] README with architecture diagram, setup steps, screenshots
- [ ] 3-minute demo video: buy → pay → assign → deliver → points → ask the chatbot

---

## 7. Assumptions and open questions

| # | Item | Current assumption |
|---|---|---|
| 1 | ">$50 = 1 or 2 points" | 1 point normal, 2 points for subscribers |
| 2 | Subscription prices | e.g. $4.99/month, $49.99/year — to be decided |
| 3 | City area | Phnom Penh only |
| 4 | Delivery companies | Admin-managed list; verify real companies serving target provinces |
| 5 | Delivery fee rules | Distance bands for city, company base fee for province |
| 6 | Points expiry | 1 year after earning |
| 7 | KHQR | Sandbox only for the portfolio version |
| 8 | Unpaid order timeout | 15 minutes |
