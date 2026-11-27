# Task 4 — Phase 4: Cart, checkout & orders (3 agents in parallel)

Customers fill a cart and check out in **one transaction** that reserves stock; staff then move the order
through its status flow until it is delivered. KHQR orders wait for payment (Phase 5) and are cancelled after
15 minutes; COD orders are confirmed straight away.

Already done (on `main`): Phase 1 (users, addresses, delivery companies), Phase 2 (catalog, inventory),
Phase 3 (audit logs, notifications). Same workflow as `task/task3.md`.

Read first: `CLAUDE.md` (especially **Stock and money flows**), `PRD.md` §3.2 Addresses and delivery,
§3.5 Cart and checkout, §3.6 Orders, `architecture.md` §4.2 (Shopping & orders), §5.1 Checkout,
§5.3 Fulfilment, §5.4 Cancellation / expiry, §5.5 Pricing order.

**Not in this phase:** KHQR QR codes, the payment webhook and the payment-check job (Phase 5 — orders get a
`PENDING` payment row now, with no QR), promotions / coupons (Phase 6), reward vouchers and points
(Phase 7), free delivery for subscribers (Phase 8), the outbox and RabbitMQ (Phase 9 — use
`ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`; ShedLock comes **now**, see
Step 0), auto-assigning the least-busy
driver (P1), refunds (need a payment, Phase 5), frontend screens.

---

## How the work is split

| | Agent A — Cart & checkout | Agent B — Orders & fulfilment | Agent C — Pricing & delivery |
|---|---|---|---|
| Branch | `task4/agent-a-checkout` | `task4/agent-b-orders` | `task4/agent-c-pricing` |
| Services | `CartService`, `CheckoutService` | `OrderService`, `AdminOrderService`, `OrderExpiryJob` | `PricingCalculator` (pure), `DeliveryFeeCalculator` (pure), `DeliveryQuoteService` |
| Controllers | `CartController`, `CheckoutController` | `OrderController`, `AdminOrderController` | `CheckoutQuoteController` |

**Step 0** (lead, on `main`) creates the tables, entities, the order state machine's statuses, the
cross-module lookups and working first versions of the pricing / delivery contract, so no agent waits.

---

## Permissions (PRD §2.1)

| Feature | ADMIN | MANAGER | STAFF | CUSTOMER |
|---|---|---|---|---|
| Cart, checkout, quote | ❌ | ❌ | ❌ | ✅ |
| My orders (list, detail, timeline, cancel before processing) | — | — | — | ✅ own only |
| All orders (list, detail), change status, assign driver, tracking number | ✅ | ✅ | ✅ | ❌ |
| Cancel any order before delivery | ✅ | ✅ | ❌ | ❌ |

---

## Order status flow (PRD §3.6)

```
KHQR:  PENDING_PAYMENT ─(Phase 5: paid)─► PAID ─► PROCESSING ─► OUT_FOR_DELIVERY  ─► DELIVERED   (CITY)
                                                             └► HANDED_TO_COURIER ─► DELIVERED   (PROVINCE)
COD:   CONFIRMED ─► PROCESSING ─► OUT_FOR_DELIVERY ─► DELIVERED                                   (CITY only)
Any state before OUT_FOR_DELIVERY / HANDED_TO_COURIER ─► CANCELLED (stock released)
PENDING_PAYMENT past expires_at ─► CANCELLED by the expiry job
DELIVERED ─► REFUNDED (Phase 5)
```

`OrderStatus.canMoveTo(next, deliveryType)` (Step 0) is the single source of truth for these arrows.

---

## Step 0 — shared contract ✅ done (on `main`)

Already built and tested. Every agent builds on these files and **must not change them**.

**Things to know before you start:**
- **Stock:** call `StockService.reserve(Map<productId, qty>, orderId, userId)` / `release(...)`. They sort by
  product id themselves. (`remove` / `receive` take `MovementReason`, which is in `inventory.entity` — other
  modules can't use it, ArchUnit.)
- **Enums across modules:** other modules can't use `order.entity` (or `notification.entity`). Events carry
  statuses as strings; `OrderLookupService.summary(orderId)` returns an `OrderSummary` DTO for listeners.
  So the order notification listener lives **in the notification module** (`notification/listener/`), like the
  low-stock one.
- **Pricing:** `PricingCalculator` is a bean (registered in `order/config/OrderConfig`, because the pricing
  package may not use Spring) — inject it.
- **Time:** inject `java.time.Clock` for "now". In integration tests it is a `TestClock`
  (`clock.advance(Duration.ofMinutes(16))`), reset after every test; scheduling is off
  (`app.scheduling.enabled=false`), so call job methods directly.
- **Locks ready to use:** `CartRepository.lockByUserId` (checkout), `OrderRepository.lockByPublicId` /
  `lockById` (status changes), `OrderRepository.findIdsByStatusAndExpiresAtBefore` (expiry job).
- **`OrderFixtures`** (bean in every `IntegrationTest`): `customer()`, `driver()`, `cityAddress(user)` (at the
  shop's default location), `provinceAddress(user, company)`, `deliveryCompany(baseFee)`, `cart(user,
  Map<Product, qty>)`, `order(user, address, method, status, Map<Product, qty>)` — a full order with stock
  reserved and a PENDING payment.
- Examples: `order/service/Step0OrderSchemaTest`, `order/entity/OrderStatusTest`.

### 1. `V4__orders_and_payments.sql`

| Table | Columns |
|---|---|
| `carts` | `id`, `user_id` UNIQUE → users, `created_at`, `updated_at` |
| `cart_items` | `id`, `cart_id` → carts ON DELETE CASCADE, `product_id` → products, `quantity` INT CHECK 1–99, `created_at`, `updated_at`; UNIQUE (`cart_id`, `product_id`) |
| `orders` | `id`, `public_id` UUID, `order_no` UNIQUE DEFAULT `'ORD-' \|\| LPAD(nextval('order_no_seq'), 6, '0')`, `user_id` → users, `status`, `payment_method` (`KHQR`/`CASH_ON_DELIVERY`), `expires_at` NULL; **delivery snapshot**: `delivery_type`, `receiver_name`, `receiver_phone`, `province`, `shipping_address`, `shipping_latitude`, `shipping_longitude`, `shipping_landmark`, `delivery_company_id` NULL, `delivery_company_name` NULL, `courier_branch` NULL, `tracking_no` NULL, `assigned_staff_id` NULL → users, `assigned_at`; **money** NUMERIC(12,2): `subtotal`, `product_discount` 0, `order_discount` 0, `reward_discount` 0, `delivery_fee`, `total_amount`; `note`, `created_at`, `updated_at`. CHECK `total_amount >= 0`. Indexes: `(user_id, created_at DESC)`, `(status)`, `(status, expires_at)`, `(created_at)` |
| `order_items` | `id`, `order_id` → orders ON DELETE CASCADE, `product_id` → products, `product_name`, `sku`, `quantity` > 0, `free_quantity` 0, `unit_price`, `discount_amount` 0, `line_total` |
| `order_status_history` | `id`, `order_id` → orders, `status`, `changed_by` → users NULL (null = system job), `note`, `created_at` |
| `payments` | `id`, `public_id`, `payment_type` (`ORDER` now; `SUBSCRIPTION` in Phase 8), `order_id` → orders, `method`, `amount`, `currency` (`USD`), `status` (`PENDING`/`SUCCESS`/`FAILED`/`CANCELLED`/`REFUNDED`), `transaction_ref` UNIQUE NULL, `qr_string` NULL, `qr_md5` NULL, `expires_at` NULL, `raw_webhook` JSONB NULL, `paid_at` NULL, `created_at`, `updated_at` |

Plus **`shedlock`** (the standard ShedLock JDBC table): `CLAUDE.md` requires ShedLock on every `@Scheduled`
job, and the expiry job is the first one (`architecture.md` had it in Phase 9).

Loyalty and promotion links (`reward_redemption_id`, `is_member_order`, `points_earned`, `order_promotions`,
`payments.user_subscription_id`) are added by the migrations of their phases.

### 2. Entities + enums
`Cart`, `CartItem`, `Order`, `OrderItem`, `OrderStatusHistory`, `Payment` (order module; payments move to the
payment module in Phase 5), `OrderStatus` (with `canMoveTo`), `PaymentMethod`, `PaymentStatus`,
`DeliveryType` (order's own copy). New `NotificationType`s: `NEW_ORDER`, `ORDER_STATUS_CHANGED`,
`ORDER_CANCELLED`. New `AuditEntity.ORDER`.

### 3. Cross-module lookups (other modules expose DTOs, never entities)
- **catalog** `ProductCatalogService.sellable(Collection<Long> productIds)` → `Map<Long, SellableProduct>`
  (`id, publicId, slug, sku, name, sellPrice, unit, mainImageUrl, quantity, purchasable`, `inStock()`) — `purchasable` = product
  **and** its category (and parent) active. Plus `sellable(UUID publicId)`.
- **user** `AddressLookupService.requireOwned(Long userId, UUID addressPublicId)` → `AddressSnapshot`
  (all delivery fields, `singleLine()`; another user's address = 404), `AddressLookupService.insidePhnomPenh(lat, lng)`
  (the address form's box), and `UserDirectoryService.isActiveDriver(Long userId)`, `driverIds()` (active STAFF
  with position "Driver").
- **delivery** `DeliveryCompanyService.snapshot(Long id)` → `DeliveryCompanySnapshot(id, name, baseFee,
  trackingUrl, active)`.
- **inventory** `StockService.reserve(Map<Long, Integer>, orderId, userId)` (OUT, RESERVE) and `release(...)`
  (IN, RELEASE), both **in product id order** (the deadlock fix from Phase 2).
- **order** `OrderLookupService.summary(orderId)` → `OrderSummary` (for listeners in other modules).

### 4. Pricing & delivery contract (Agent C replaces the bodies, not the signatures)
- `order.service.pricing.PricingCalculator.price(PricingInput) → PricingResult` — pure (ArchUnit
  `pricingIsPureLogic`). Input: lines (`productId, quantity, unitPrice`) + `deliveryFee`. Result: per-line
  totals, `subtotal`, the three discounts (0 for now), `deliveryFee`, `total` (≥ 0). Step 0 version: subtotal +
  fee.
- `order.service.delivery.DeliveryQuoteService.quote(AddressSnapshot, PaymentMethod) → DeliveryQuote(fee,
  companySnapshot)` — validates the delivery rules and throws 422 with a clear message. Step 0 version: CITY flat
  $1, PROVINCE company base fee, no other checks.

### 5. Scheduling and time
ShedLock dependencies (`shedlock-spring`, `shedlock-provider-jdbc-template`) + `@EnableScheduling` /
`@EnableSchedulerLock`, and a `Clock` bean. Code that needs "now" (expiry, `expires_at`) takes the `Clock`, so
tests can move time instead of sleeping. Scheduling is **off in tests** (`app.scheduling.enabled=false`); tests
call the job method directly.

### 6. Events (ids only; consumers reload)
`order.event.OrderPlacedEvent(orderId)`, `OrderStatusChangedEvent(orderId, from, to, note)` (statuses as
strings) — published inside the transaction.

### 7. Test support
`OrderFixtures`, `TestClock`, `Step0OrderSchemaTest` and `OrderStatusTest` (see above).

---

## File ownership

| File / area | Owner |
|---|---|
| Step 0 files (migration, entities, enums, repositories, `OrderStatus`, events, `OrderLookupService`, the lookups in catalog / user / delivery / inventory, the two contract signatures, `OrderConfig`, `SchedulingConfig`, `OrderFixtures`, `TestClock`) | **lead only** |
| `order/service/cart/**`, `order/service/checkout/**`, `CartController`, `CheckoutController`, cart & checkout DTOs | Agent A |
| `order/service/orders/**`, `OrderController`, `AdminOrderController`, order DTOs / mapper, `OrderExpiryJob`, `notification/listener/OrderNotificationListener` | Agent B |
| Bodies of `PricingCalculator`, `DeliveryQuoteService`; `DeliveryFeeCalculator`; `CheckoutQuoteController`; `app.shop.*` / `app.delivery.*` config | Agent C |
| `SecurityConfig`, `common/exception/**`, `common/handler/**`, Flyway, other modules' code | **nobody** (lead only) |

---

## Agent A — Cart & checkout

### Build
- **`CartService`**: get my cart (created on first use), add (`productSlug` or `productPublicId`, quantity;
  adding again adds up, max 99 per line, max 50 lines), set quantity, remove line, clear. Only purchasable
  products can be added (422). The cart response shows **current** prices from `ProductCatalogService`, the line
  totals, the subtotal, and per line `available` / `inStock` (so the UI can warn before checkout). Never
  quantities of stock.
- **`CheckoutService.checkout(userId, CheckoutRequest{addressPublicId, paymentMethod, note})`** — one
  `@Transactional`, in the order of `architecture.md` §5.1:
  1. Load the cart; empty → 422. Every product still purchasable (422 naming the product).
  2. Prices from the DB (`ProductCatalogService`), never from the client.
  3. `DeliveryQuoteService.quote(address, method)` (422 on rule violations).
  4. `PricingCalculator.price(...)`.
  5. Insert the order (snapshots of address, company name, product names, SKUs, unit prices) + items +
     first status history row (`PENDING_PAYMENT` with `expires_at = now + 15 min` for KHQR, `CONFIRMED` for
     COD).
  6. **Reserve stock** via `StockService.reserve(quantities, orderId, userId)` (it sorts by product id). Not
     enough → 409 `INSUFFICIENT_STOCK` naming the product, and **everything rolls back** (no order, cart kept).
  7. Insert the `PENDING` payment row (amount = total, same `expires_at`; no QR until Phase 5).
  8. Clear the cart. Publish `OrderPlacedEvent`.
  9. Return the order (`publicId`, `orderNo`, status, totals, `expiresAt`).
- Idempotency: an optional `Idempotency-Key` header is **not** in scope; a double click creates a second
  order only if the cart still has items — it was cleared by the first, so the second gets 422 "cart is empty".

### Endpoints (CUSTOMER)
| Method | Path |
|---|---|
| GET | `/api/cart` |
| POST | `/api/cart/items` (`productPublicId`, `quantity`) |
| PUT | `/api/cart/items/{productPublicId}` (`quantity`) |
| DELETE | `/api/cart/items/{productPublicId}` · `/api/cart` |
| POST | `/api/checkout` → 201 |

### Tests (at least)
- `addToCart_sameProductTwice_addsUp`, `addToCart_inactiveProduct_returns422`, `cart_priceChanged_showsNewPrice`
- `checkout_cod_confirmedStockReservedCartCleared` (movements: one `OUT RESERVE` per line, reference = order)
- `checkout_khqr_pendingPaymentExpiresIn15Minutes`
- `checkout_notEnoughStock_409AndNothingSaved` (no order, no movement, cart unchanged)
- `checkout_otherUsersAddress_returns404`, `checkout_emptyCart_returns422`, `checkout_clientSentPrice_ignored`
- **`checkout_tenBuyersThreeLeft_exactlyThreeOrders`** — the `CLAUDE.md` launch test at the API level: 10
  customers, each with the product in their cart, check out at once → exactly 3 orders, 7 × 409, stock 0,
  exactly 3 `RESERVE` movements.

---

## Agent B — Orders & fulfilment

### Build
- **Customer** (`OrderService`): my orders (paginated, newest first, `status` filter), my order detail with
  items, money breakdown, delivery snapshot, tracking link (company template with `{tracking_no}`) and the
  **timeline** (status history, without staff ids). Cancel my order while `PENDING_PAYMENT` or `CONFIRMED`.
  Another user's order = 404.
- **Staff** (`AdminOrderService`): list with filters (`status`, `deliveryType`, `from`/`to`, `q` = order no /
  receiver phone, `assignedTo`), detail (adds customer email, assigned driver), and:
  - `PATCH /status` — only arrows allowed by `OrderStatus.canMoveTo` (else 409 `INVALID_STATUS`).
    `OUT_FOR_DELIVERY` needs an assigned driver (CITY); `HANDED_TO_COURIER` needs a tracking number
    (PROVINCE).
  - `PUT /driver` — CITY only; must be an active driver (`UserDirectoryService.isActiveDriver`).
  - `PUT /tracking` — PROVINCE only.
  - Cancel (ADMIN/MANAGER) any order before it leaves the shop.
- **Every status change** (lock the order row first, `SELECT … FOR UPDATE`): history row with `changed_by`,
  `OrderStatusChangedEvent`, and on **CANCELLED**: release every item's stock
  (`StockService.release(..)`, reference = order) and mark the payment `CANCELLED`. Cancelling twice = 409, stock released once.
- **`OrderExpiryJob`**: `@Scheduled` every minute with `@SchedulerLock`, `PENDING_PAYMENT` orders past `expires_at` → CANCELLED as above
  (`changed_by` null, note "Not paid in time"). Each order in its own transaction; idempotent (re-check status
  under the lock).
- **`notification/listener/OrderNotificationListener`** (AFTER_COMMIT, reloads with `OrderLookupService`, new transaction, failures only logged): `OrderPlacedEvent`
  for COD → `NEW_ORDER` to ADMIN/MANAGER/STAFF; status changes → `ORDER_STATUS_CHANGED` to the customer
  (cancellation → `ORDER_CANCELLED`, with the reason). KHQR "new paid order" comes in Phase 5.

### Endpoints
| Method | Path | Auth |
|---|---|---|
| GET | `/api/orders?status=&page=&size=` · `/api/orders/{publicId}` | CUSTOMER |
| POST | `/api/orders/{publicId}/cancel` | CUSTOMER |
| GET | `/api/admin/orders?…` · `/api/admin/orders/{publicId}` | STAFF+ |
| PATCH | `/api/admin/orders/{publicId}/status` (`status`, `note`) | STAFF+ |
| PUT | `/api/admin/orders/{publicId}/driver` · `/tracking` | STAFF+ |
| POST | `/api/admin/orders/{publicId}/cancel` (`reason`) | ADMIN, MANAGER |

### Tests (at least)
- `myOrders_onlyMine`, `orderDetail_otherCustomer_returns404`, `timeline_hidesStaffIds`
- `changeStatus_skippingAStep_returns409`, `outForDelivery_withoutDriver_returns422`,
  `handedToCourier_withoutTrackingNo_returns422`, `assignDriver_notADriver_returns422`
- `cancel_confirmedOrder_releasesStockOncePaymentCancelled` (twice → 409, one `IN RELEASE` per line)
- `customerCancel_afterProcessing_returns409`
- `expiryJob_pastExpiry_cancelsAndReleases`, `expiryJob_notExpired_untouched`
- `statusChange_notifiesCustomer`, `codOrderPlaced_notifiesStaff`

---

## Agent C — Pricing & delivery

### Build
- **`PricingCalculator`** (pure, no Spring): line total = unit price × quantity; subtotal; discounts 0 (hooks
  for Phases 6–7 documented in Javadoc); total = subtotal − discounts + fee, never below 0; every amount
  `HALF_UP`, scale 2. Fully unit-tested (no Spring context).
- **`DeliveryFeeCalculator`** (pure): Haversine distance from the shop to the pin, then bands from config:
  `app.delivery.city-bands` (default `< 5 km → 1.00`, `5–15 km → 2.00`, `> 15 km → 3.00`), shop location
  `app.shop.latitude/longitude` (default Phnom Penh centre `11.5564, 104.9282`).
- **`DeliveryQuoteService.quote`** (PRD §3.2): CITY → pin present and inside Phnom Penh (reuse the bounds the
  address module uses), fee by distance; KHQR or COD. PROVINCE → company present **and active** (inactive since
  the address was saved → 422 "choose another company"), courier branch present, **KHQR only** (COD → 422), fee
  = company base fee.
- **`GET /api/checkout/quote?addressPublicId=&paymentMethod=`** (CUSTOMER): the totals the current cart would
  cost (lines, subtotal, delivery fee, total, `deliveryType`, company name) without placing an order; uses the
  same services as checkout so the numbers always match.

### Tests (at least)
- `PricingCalculatorTest`: rounding, empty discounts, total never negative, many lines
- `DeliveryFeeCalculatorTest`: each band and its edges (4.99 / 5.00 / 15.00 km), the shop itself = first band
- `quote_cityPinOutsidePhnomPenh_returns422`, `quote_provinceWithCod_returns422`,
  `quote_inactiveCompany_returns422`, `quote_matchesCheckoutTotal`

---

## Rules for every agent

Same as Task 3: follow `CLAUDE.md`; constructor injection; thin controllers; `@Transactional` on writing service
methods; DTO records only; lists paginated (max 100) with a sort allowlist; test names
`methodName_condition_expectedResult`; integration tests extend `IntegrationTest` and use unique names/emails.
Money is `BigDecimal` scale 2 everywhere; never trust a client-sent price or total. Stock only through
`StockService`, always in product id order. No `Co-Authored-By` lines. Don't touch Flyway — need a schema
change? Ask the lead. Before finishing: `cd backend && ./mvnw test` passes; endpoints look right in Swagger.

## Merge order

1. Step 0 → `main`.
2. Agents work in parallel from that `main`.
3. Merge **C → B → A** (A's checkout uses C's real pricing and fees, and B's cancel path; A rebases last and
   re-runs its tests, which must assert totals from the quote, not hard-coded fees).
4. After all three, one end-to-end test on `main`: customer adds 2 products → quote → COD checkout (stock down,
   staff notified) → staff: PROCESSING → assign driver → OUT_FOR_DELIVERY → DELIVERED, customer sees the timeline
   and gets notifications; second customer KHQR checkout → expiry job after 15 min (clock moved) → CANCELLED,
   stock back, payment CANCELLED.

## Done when

- [x] Step 0 merged
- [ ] Agent C merged
- [ ] Agent B merged
- [ ] Agent A merged
- [ ] End-to-end test passes (incl. 10 buyers / 3 left at the API level); `CLAUDE.md` "Current phase" → Phase 4 done
