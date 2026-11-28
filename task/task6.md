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

## Step 0 — shared contract ✅ done (on `main`)

Already built and tested. Every agent builds on these files and **must not change them**.

**Things to know before you start:**
- **Item offers list their products.** `PromotionOffer.productIds` / `PricingInput.Offer.productIds` hold exactly
  the cart product ids an ITEM offer applies to: `offersFor` fills every cart line for `apply_to = ALL` and
  resolves CATEGORIES (the category or its parent). There is **no "empty = all"**: an ITEM offer that matches no
  line is not returned. ORDER offers have an empty set.
- **The minimum order amount is checked by pricing, not by `offersFor`** (it depends on item discounts). ORDER
  offers: subtotal after item discounts ≥ minimum; ITEM offers: subtotal before discounts ≥ minimum. Pricing
  reports what happened to the coupon in `PricingResult.couponStatus`: `NONE` (no coupon offer), `APPLIED`,
  `BELOW_MINIMUM`, `NOT_BETTER` (item coupon beats the automatic one on none of its lines). Agent C turns
  `couponProblem` and these statuses into the quote's reason / checkout's 422.
- **FREE_DELIVERY:** `PricingResult.deliveryFee` becomes 0, `orderDiscount` stays 0, and `appliedOffers` lists it
  with the waived fee as its discount (that's what `order_promotions.discount_amount` stores).
- **`PricingResult.Line.lineTotal` is before the discount** (unit price × quantity, as in Phase 4);
  `discountAmount` holds the item discount, including buy X get Y's free units (`freeQuantity × unitPrice`).
  `appliedOffers` has one entry per offer that took something off (an ITEM offer's lines summed).
- **`used_count` is read-only in JPA** (`insertable = false, updatable = false`): only
  `PromotionRepository.claimUse(id)` / `releaseUse(id)` change it, so saving a stale `Promotion` never
  overwrites it. Reload to see the current value. These queries don't clear the persistence context (checkout
  still holds its cart and order).
- **Coupon codes:** `Promotion.setCouponCode` and `Promotion.normaliseCode` trim + upper-case (blank → null).
  The DB enforces `^[A-Z0-9_-]{3,30}$` and UNIQUE. `OfferSet.couponCode` is the normalised entered code.
- **Wiring done in Step 0:** `CheckoutQuoteService.price(userId, quantities, address, method, couponCode)` calls
  `offersFor` and maps the offers into `PricingInput` (`PricedCart.offers()` keeps the `OfferSet`); the quote
  endpoint takes `?couponCode=`; `CheckoutRequest.couponCode` (max 30); checkout calls
  `PromotionUsageService.claim(orderId, userId, List<UsedPromotion>)` right after reserving stock;
  `OrderWorkflow.move` calls `release(orderId)` on CANCELLED **and** REFUNDED (customer / staff cancel, expiry,
  refund all go through it). `PromotionUsageService.forOrder(orderId)` → `List<OrderPromotionView>` already works.
- **Not wired yet (Agent C):** checkout throwing 422 `COUPON_INVALID` (`new BusinessRuleException(ErrorCode.
  COUPON_INVALID, message)`), the quote response's coupon section, order detail promotions.
- **Repositories:** agents may **add** query methods to `PromotionRepository` / `OrderPromotionRepository`;
  don't change the existing ones. `findWithTargetsById` loads targets (they're lazy).
- **Fixtures:** `PromotionFixtures` (bean in every `IntegrationTest`): `percentageOnProducts`,
  `percentageOnCategory`, `percentageOnAll`, `fixedOnProducts`, `buyXGetY`, `orderPercentage(percent, min)`,
  `freeDelivery(min)`, `coupon(code, amount, min, usageLimit, perUserLimit)`, `uniqueCode(prefix)`,
  `save(type, applyTo, customize)` for anything else, `reload`, `usedCount`, `setUsedCount`, `orderPromotions`.
  Defaults: active, started 1 h ago (test clock), ends in 30 days.

### 1. `V6__promotions.sql`
- `promotions`: `id`, `public_id`, `name`, `description`, `banner_url`, `type` (`PERCENTAGE`/`FIXED_AMOUNT`/
  `BUY_X_GET_Y`/`FREE_DELIVERY`), `discount_value` NULL, `max_discount` NULL, `buy_quantity` / `get_quantity` NULL
  (only for BUY_X_GET_Y), `apply_to` (`ALL`/`PRODUCTS`/`CATEGORIES`/`ORDER`), `min_order_amount` NULL,
  `coupon_code` UNIQUE NULL, `members_only`, `usage_limit` NULL, `per_user_limit` NULL, `used_count` (CHECK
  `>= 0` and `<= usage_limit`), `start_date`, `end_date`, `is_active`, **`broadcast_on_start`** (for
  `PromotionLaunchJob`), `broadcast_at` NULL, `created_by` → users, timestamps. CHECKs: percentage 0–100, fixed > 0,
  value required except BXGY / free delivery, BXGY needs X ≥ 1 and Y ≥ 1 and not ORDER, FREE_DELIVERY only
  ORDER, `max_discount > 0`, limits ≥ 1, `end_date > start_date`. Index `(is_active, start_date, end_date)`.
  Agent A: lowering `usage_limit` below `used_count` fails the CHECK, so validate it (422) first.
- `promotion_targets`: `promotion_id` → promotions ON DELETE CASCADE, `product_id` | `category_id` (exactly one),
  UNIQUE per target.
- `order_promotions`: `order_id`, `promotion_id`, `promotion_name`, `coupon_code`, **`level`** (`ITEM`/`ORDER`),
  `discount_amount`, `user_id`, `released_at`, `created_at`; UNIQUE `(order_id, promotion_id)`; partial index
  `(promotion_id, user_id) WHERE released_at IS NULL` for per-user limits.
- `order_items`: CHECK `free_quantity <= quantity`.

### 2. Entities + enums (`promotion.entity`) and contract changes elsewhere
- `Promotion` (with `targets`, `addProductTarget`, `addCategoryTarget`, `isRunningAt(now)`, `isCoupon()`),
  `PromotionTarget`, `OrderPromotion` (`of(...)` snapshot), `PromotionType`, `PromotionScope` (`apply_to`,
  `isItemLevel()`), `DiscountLevel` (`ITEM`/`ORDER`).
- `SellableProduct` gains `categoryId` and `parentCategoryId`.
- `OrderItem.stockQuantity()` = `quantity` (free units are part of it).
- `CheckoutRequest.couponCode`, quote `?couponCode=`.

### 3. Contracts
- **Pricing** (`order.service.pricing`, Agent B fills `PricingCalculator.price`):
  `PricingInput(lines, deliveryFee, List<Offer> offers)` (2-arg constructor = no offers; at most one coupon offer);
  `Offer(id, name, OfferType, OfferLevel, value, maxDiscount, buyQuantity, getQuantity, minOrderAmount,
  Set<Long> productIds, boolean coupon, Instant endsAt)`. `PricingResult` gains `appliedOffers`
  (`AppliedOffer(offerId, level, discountAmount)`) and `couponStatus`; `Line` gains `appliedOfferId`.
  Step 0 version: ignores offers.
- **Eligibility** (`promotion.service.PromotionService`, Agent C):
  `offersFor(userId, List<PromotionCartLine(productId, categoryId, parentCategoryId)>, couponCode, now)` →
  `OfferSet(List<PromotionOffer> offers, couponCode, couponProblem)`. `PromotionOffer` carries enum values as
  names (`type`, `level` = "ITEM"/"ORDER"), `couponCode` null for automatic. Step 0 version: no offers.
- **Usage** (`promotion.service.PromotionUsageService`, Agent C): `claim(orderId, userId,
  List<UsedPromotion(promotionId, level, discountAmount)>)` — `claimUse` per promotion (0 rows → 409
  `PROMOTION_EXHAUSTED`) + `order_promotions` rows; `release(orderId)` — once per row (`released_at`) +
  `releaseUse`. Both `Propagation.MANDATORY`. Step 0 versions: no-ops.
- `ErrorCode.PROMOTION_EXHAUSTED` (409), `ErrorCode.COUPON_INVALID` (422) + `BusinessRuleException(ErrorCode,
  String)`; `NotificationType.PROMOTION`; `AuditEntity.PROMOTION`.

### 4. Test support
`PromotionFixtures` (see above) and `promotion/service/Step0PromotionSchemaTest`.

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

- [x] Step 0 merged
- [x] Agent B merged
- [x] Agent A merged
- [x] Agent C merged
- [x] End-to-end test passes (`PromotionEndToEndTest`); `CLAUDE.md` "Current phase" → Phase 6 done
