# Task 6 — Phase 6: Promotions & coupons (3 agents in parallel)

Managers create promotions (percentage, fixed amount, buy X get Y, free delivery), automatic or with a coupon
code; checkout applies the **best automatic promotion per item + one order-level promotion** (a coupon, or the
best automatic order promotion), the total never goes below $0, and usage limits hold even under concurrent
checkouts.

Already done (on `main`): Phases 1–5 (users, catalog & inventory, audit & notifications, cart / checkout /
orders, KHQR payments — the MVP). Same workflow as `task/task5.md`.

Read first: `CLAUDE.md` (**Pricing order** — pure, unit-tested logic), `PRD.md` §3.8 Promotions,
`architecture.md` §4.2 (Promotions tables), §5.1 Checkout steps 3 and 8, §5.4 (usage released on cancel),
§5.5 Pricing order.

**Not in this phase:** reward vouchers (Phase 7 — the third discount slot stays 0), points, members-only
promotions actually applying (Phase 8 has subscriptions; until then nobody is a member, so `members_only`
promotions are saved but never apply), RabbitMQ broadcast in batches (Phase 9 — the broadcast is in-process
now), sale prices on product listings (the discount shows in the quote and the order), frontend screens.

---

## How the work is split

| | Agent A — Promotion admin & storefront | Agent B — Pricing engine | Agent C — Checkout, coupons & usage |
|---|---|---|---|
| Branch | `task6/agent-a-promotions` | `task6/agent-b-pricing` | `task6/agent-c-usage` |
| Builds | promotion CRUD, targets, status, public list, broadcast + launch job | `PricingCalculator` with promotions (pure, exhaustively unit-tested) | eligibility, coupon in quote / checkout, atomic usage, `order_promotions`, release on cancel |

**Step 0** (lead, on `main`) adds the tables, entities, and the contracts below, so no agent waits.

---

## Permissions (PRD §2.1)

| Feature | ADMIN | MANAGER | STAFF | CUSTOMER |
|---|---|---|---|---|
| Create / edit / deactivate promotions, broadcast | ✅ | ✅ | ❌ | ❌ |
| See all promotions (incl. coupons and usage) | ✅ | ✅ | ❌ | ❌ |
| See active automatic promotions (storefront) | public | | | |
| Use a coupon in quote / checkout | — | — | — | ✅ |

---

## Pricing rules (PRD §3.8, `architecture.md` §5.5)

```
line total      = unit price × quantity
item discount   = the BEST automatic item promotion for that line (only one per line):
                    PERCENTAGE   value % of the line total (capped by max_discount)
                    FIXED_AMOUNT value × quantity, never more than the line total
                    BUY_X_GET_Y  per line (same product): every (X+Y) units, Y are free → free_quantity
                  ties → the promotion that ends first, then the lowest id (deterministic)
subtotal after items = subtotal − item discounts
order discount  = ONE order-level promotion: the coupon if the customer entered a valid one, otherwise the
                  best automatic ORDER promotion. min_order_amount is checked against "subtotal after items".
                    PERCENTAGE / FIXED_AMOUNT on "subtotal after items" (PERCENTAGE capped by max_discount)
                    FREE_DELIVERY → delivery fee becomes 0 (the discount amount is the fee)
reward discount = 0 (Phase 7)
total           = subtotal − item discounts − order discount − reward discount + delivery fee, never < 0
```
Every amount `BigDecimal`, scale 2, `HALF_UP`, rounded per line. A coupon on an ITEM-scope promotion
(`apply_to` PRODUCTS / CATEGORIES / ALL) is applied to the matching lines **instead of** their automatic one when
it gives more, and it still takes the order slot (one coupon per order).

---

## Step 0 — shared contract (lead, on `main`)

Every agent builds on these files and **must not change them**.

### 1. `V6__promotions.sql`
- `promotions`: `id`, `public_id`, `name`, `description`, `banner_url`, `type` (`PERCENTAGE`/`FIXED_AMOUNT`/
  `BUY_X_GET_Y`/`FREE_DELIVERY`), `discount_value` NUMERIC NULL, `max_discount` NULL, `buy_quantity` NULL,
  `get_quantity` NULL, `apply_to` (`ALL`/`PRODUCTS`/`CATEGORIES`/`ORDER`), `min_order_amount` NULL,
  `coupon_code` UNIQUE NULL (stored upper-case; null = automatic), `members_only`, `usage_limit` NULL,
  `per_user_limit` NULL, `used_count` INT default 0 CHECK `used_count >= 0` and `<= usage_limit`, `start_date`,
  `end_date`, `is_active`, `broadcast_at` NULL, `created_by` → users, timestamps. CHECKs per type (e.g.
  BUY_X_GET_Y needs both quantities; FREE_DELIVERY only with `apply_to = ORDER`; `end_date > start_date`).
  Index `(is_active, start_date, end_date)`.
- `promotion_targets`: `promotion_id` → promotions ON DELETE CASCADE, `product_id` NULL, `category_id` NULL —
  exactly one (CHECK); UNIQUE per target.
- `order_promotions`: `order_id`, `promotion_id`, `promotion_name` (snapshot), `coupon_code` NULL, `scope`
  (`ITEM`/`ORDER`), `discount_amount`, `user_id`, `released_at` NULL (usage given back on cancel), `created_at`.
  Index `(promotion_id, user_id)` for per-user limits.

### 2. Entities + enums (promotion module) and two contract changes elsewhere
- `Promotion`, `PromotionTarget`, `OrderPromotion`, `PromotionType`, `PromotionScope` (`apply_to`).
- `SellableProduct` gains `categoryId` and `parentCategoryId` (to match CATEGORIES targets, incl. subcategories).
- `OrderItem.free_quantity` means **free units included in `quantity`** (buy 2 get 1 = quantity 3, free 1), so
  `stockQuantity()` = `quantity`. (Phase 4 assumed extra units; nothing used it yet.)
- `CheckoutRequest` and the quote take an optional `couponCode`.

### 3. Contracts
- **Pricing (pure, Agent B)** — `PricingInput` gains `List<PricingInput.Offer> offers` (id, name, type, scope,
  value, maxDiscount, buy, get, minOrderAmount, `productIds` it applies to (empty = all), `coupon` flag,
  `endsAt`) and `isCouponEntered`; `PricingResult` gains per line `appliedOfferId`, and `appliedOffers` (id, scope,
  discount). Step 0 version: ignores offers (same results as today).
- **Eligibility (Agent C)** — `PromotionService.offersFor(userId, List<CartLine(productId, categoryId,
  parentCategoryId)>, String couponCode, Instant now)` → `OfferSet(offers, couponProblem)`: active, in date,
  usage left, per-user limit left, members-only skipped (Phase 8), coupon looked up case-insensitively.
  Step 0 version: no offers.
- **Usage (Agent C)** — `PromotionUsageService.claim(orderId, userId, appliedOffers)` inside checkout:
  `UPDATE promotions SET used_count = used_count + 1 WHERE id = :id AND (usage_limit IS NULL OR used_count <
  usage_limit)` (0 rows → 409 `PROMOTION_EXHAUSTED`, whole checkout rolls back) + `order_promotions` rows;
  `release(orderId)` on cancel / expiry / refund (sets `released_at`, gives the use back once).
  Step 0 version: no-ops. Order workflow and checkout already call them.
- New `ErrorCode.PROMOTION_EXHAUSTED` (409) and `COUPON_INVALID` (422); `NotificationType.PROMOTION`;
  `AuditEntity.PROMOTION`.

### 4. Test support
`PromotionFixtures` (automatic % / fixed / BXGY / free delivery, a coupon, with targets and limits) and
`Step0PromotionSchemaTest`.

---

## File ownership

| File / area | Owner |
|---|---|
| Step 0 files (migration, entities, enums, contract records and signatures, the calls from checkout / workflow, `SellableProduct` change, fixtures) | **lead only** |
| `promotion/controller/**` (admin + public), promotion DTOs / mapper, `AdminPromotionService`, `PromotionLaunchJob`, broadcast | Agent A |
| Body of `PricingCalculator.price` and its helpers in `order/service/pricing/**` | Agent B |
| Bodies of `PromotionService.offersFor`, `PromotionUsageService`; coupon handling in `CheckoutQuoteService` / quote response; `order_promotions` in order detail responses | Agent C |
| `SecurityConfig`, `common/exception/**`, Flyway | **lead only** |

---

## Agent A — Promotion admin & storefront

### Build
- **`AdminPromotionService`** + `/api/admin/promotions` (ADMIN, MANAGER): create, update, get, list
  (`status` = `SCHEDULED`/`ACTIVE`/`ENDED`/`INACTIVE` computed from dates + flag, `type`, `q`, coupon / automatic),
  deactivate (soft). Validation per type (422 with field details): percentage 1–100, fixed > 0, BXGY X ≥ 1 and Y ≥ 1,
  FREE_DELIVERY only `ORDER`; targets required for PRODUCTS / CATEGORIES (must exist); coupon code 3–30 chars
  `[A-Z0-9_-]`, unique (409); dates `end > start`. **Changing type, value, targets or coupon of a promotion that has
  been used → 409** (deactivate and create a new one, so old orders keep their meaning). Audit rows
  (`AuditEntity.PROMOTION`). Response includes `usedCount`, `usageLimit`, `status`.
- **Public** `GET /api/public/promotions`: active **automatic** promotions (name, description, banner, type, value,
  ends at, targets as product slugs / category slugs). Never coupon codes, limits or usage.
- **Broadcast**: `POST /api/admin/promotions/{id}/broadcast` → `NotificationType.PROMOTION` to every active
  customer (`notifyRoles({"CUSTOMER"})`), once (`broadcast_at`; second time → 409). **`PromotionLaunchJob`**
  (`@Scheduled` every 10 min + `@SchedulerLock`): promotions that just started and were scheduled with
  `broadcast = true` are broadcast automatically.

### Tests (at least)
- `createPromotion_eachTypeValidated`, `createPromotion_duplicateCoupon_409`, `updatePromotion_afterUse_409`
- `listPromotions_statusFromDates`, `publicPromotions_hidesCouponsAndInactive`
- `broadcast_notifiesActiveCustomersOnce`, `launchJob_startedPromotion_broadcastOnce`
- `promotions_staff_403`, `promotions_customer_403`

---

## Agent B — Pricing engine (pure)

### Build
- `PricingCalculator.price` implementing **Pricing rules** above exactly: best item offer per line (incl. BXGY
  `free_quantity`), the order slot (coupon first, else best automatic ORDER offer; min order amount on subtotal
  after item discounts; FREE_DELIVERY zeroes the fee), caps, never-negative total, deterministic ties.
- No Spring, no repositories (ArchUnit `pricingIsPureLogic`). Pure JUnit tests, no Spring context.

### Tests (at least) — this is the core money logic, be exhaustive
- each type alone; `max_discount` cap; fixed larger than the line → line becomes 0, never negative
- BXGY: 2 / 3 / 5 / 6 units with buy 2 get 1 → 0 / 1 / 1 / 2 free
- two automatic offers on one line → the larger; equal → earliest end, then lowest id
- min order amount just below / equal / above (against subtotal after item discounts)
- coupon beats the automatic order offer even when smaller (customer's choice), and only one order offer applies
- FREE_DELIVERY with fee 0, with fee > 0, below the minimum
- huge discounts → total 0, never negative; rounding of many lines (per line, HALF_UP)
- a **table-driven** test of ~20 carts with hand-calculated totals (documented in the test)

---

## Agent C — Checkout, coupons & usage

### Build
- **`PromotionService.offersFor`**: everything that can apply right now to these lines for this user (dates,
  `is_active`, usage left, per-user limit = this user's `order_promotions` without `released_at`, members-only
  skipped), plus the coupon if one was entered — unknown, expired, used up, below the minimum, members-only or
  over the per-user limit → `couponProblem` with a clear message (no offer).
- **Quote** (`GET /api/checkout/quote?…&couponCode=`): shows line discounts, the applied promotions (names) and,
  for a coupon that doesn't apply, `coupon: { code, applied: false, reason }` — **200**, not an error.
- **Checkout**: same offers and pricing; a coupon that doesn't apply → **422 `COUPON_INVALID`** (never silently
  dropped); then `PromotionUsageService.claim` (atomic `used_count`, 409 `PROMOTION_EXHAUSTED`) and
  `order_promotions` snapshot rows; `order_items.discount_amount` / `free_quantity`; order discount columns.
- **Release**: cancel, expiry and refund give the usage back once (`release(orderId)`), so a cancelled order's
  coupon can be used again and doesn't count against the per-user limit.
- Order detail (customer and admin) lists the applied promotions (name, discount).

### Tests (at least)
- `quote_couponUnknown_200WithReason`, `checkout_couponUnknown_422NothingSaved`
- `checkout_automaticPercentage_discountSnapshotted`, `checkout_coupon_orderDiscountAndUsageCounted`
- **`checkout_couponWithLimitOne_tenBuyersConcurrently_exactlyOneUses`** (the rest get 409, stock untouched for them)
- `checkout_perUserLimit_secondOrder422`, `cancel_releasesUsage_couponUsableAgain`, `expiry_releasesUsage`
- `quote_matchesCheckoutTotal_withPromotions`

---

## Rules for every agent

Same as Task 5: follow `CLAUDE.md`; constructor injection; thin controllers; `@Transactional` on writing service
methods; DTO records only; lists paginated (max 100); test names `methodName_condition_expectedResult`;
integration tests extend `IntegrationTest`. Money is `BigDecimal` scale 2; never trust a client-sent price or
discount. No `Co-Authored-By` lines. Don't touch Flyway — ask the lead. Before finishing: `cd backend && ./mvnw test`
passes; endpoints look right in Swagger.

## Merge order

1. Step 0 → `main`.
2. Agents work in parallel from that `main`.
3. Merge **B → A → C** (C's checkout tests depend on B's real numbers; A is independent).
   After each merge, the next branch rebases on `main` and re-runs `./mvnw test`.
4. After all three, one end-to-end test on `main`: manager creates an automatic 10 % on a category, a
   buy-2-get-1 on a product and a coupon `WELCOME5` ($5 off over $20, limit 1 per user) and broadcasts it →
   customer quote shows all three → KHQR checkout → order snapshot and totals match the quote → the same customer
   can't use `WELCOME5` again → the order is cancelled → the coupon can be used again; a FREE_DELIVERY order
   over $30 has fee 0.

## Done when

- [ ] Step 0 merged
- [ ] Agent B merged
- [ ] Agent A merged
- [ ] Agent C merged
- [ ] End-to-end test passes; `CLAUDE.md` "Current phase" → Phase 6 done
