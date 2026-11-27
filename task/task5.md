# Task 5 — Phase 5: Payments (KHQR, confirmation, refunds) (3 agents in parallel)

KHQR orders get a real **KHQR code** at checkout; the shop finds out the customer paid (payment-check job and
webhook, both **idempotent**), the order moves to **PAID** and staff are told. Late payments, refunds and cash on
delivery are handled too. This is the **MVP milestone** (PRD §5, week 13).

Already done (on `main`): Phases 1–4 (users, catalog & inventory, audit & notifications, cart / checkout /
orders with a `PENDING` payment row and no QR). Same workflow as `task/task4.md`.

Read first: `CLAUDE.md` (**Stock and money flows**: "payment processing is idempotent"), `PRD.md` §3.7 Payments,
§3.6 Orders, `architecture.md` §5.2 Payment, §5.4 Cancellation, §7 Security (webhook).

**Not in this phase:** ABA PayWay and card payments (their own phase after this one — they plug into the
`PaymentGateway` interface built here), RabbitMQ / outbox (Phase 9: confirmation runs in-process; the `payment.received` →
`q.payment.process` split comes later), KHR amounts (USD only), real money movement for refunds (KHQR sandbox
can't refund: a refund is **recorded**, the money is returned by hand), subscription payments (Phase 8), points
reversal on refund (Phase 7), frontend screens (the QR is returned as the KHQR string; the UI draws it).

---

## How the work is split

| | Agent A — KHQR codes | Agent B — Payment confirmation | Agent C — Order side, COD & refunds |
|---|---|---|---|
| Branch | `task5/agent-a-khqr` | `task5/agent-b-confirm` | `task5/agent-c-refunds` |
| Builds | `KhqrService` (SDK), QR at checkout, customer payment endpoint | `PaymentProcessor`, `BakongClient`, `PaymentCheckJob`, webhook, dev "simulate payment" | PAID transition, paid-order notifications, COD collected, refunds, admin payment screens |

**Step 0** (lead, on `main`) moves payments into the `payment` module, adds the tables and the contracts
below, so no agent waits for another.

---

## Permissions (PRD §2.1)

| Feature | ADMIN | MANAGER | STAFF | CUSTOMER |
|---|---|---|---|---|
| My order's payment (QR, amount, expiry, status) | — | — | — | ✅ own only |
| Payments list / detail, payment events | ✅ | ✅ | ❌ | ❌ |
| Refund an order | ✅ | ✅ | ❌ | ❌ |
| Mark a late payment "refunded by hand" | ✅ | ✅ | ❌ | ❌ |
| Webhook | signed requests only (no JWT) | | | |
| Simulate a payment (dev / demo profile only) | ✅ | ❌ | ❌ | ✅ own order |

---

## Payment flow

```
checkout (KHQR) ─► Payment PENDING + KHQR (qr_string, qr_md5, expires_at = order.expires_at)
                         │
   confirmation source:  ├─ PaymentCheckJob: every minute, gateway.checkStatus (Bakong: by qr_md5) for PENDING payments ≥ 1 min old
                         ├─ webhook POST /api/payments/webhook/{provider} → gateway.parseCallback (signature checked)
                         └─ dev/demo: POST /api/payments/{publicId}/simulate
                         ▼
   PaymentProcessor.confirm(...)  — one transaction, payment row locked:
      every attempt → payment_events row (source, payload, outcome)
      unknown md5 → UNKNOWN_PAYMENT          amount/currency differs → AMOUNT_MISMATCH (payment untouched)
      already SUCCESS → DUPLICATE (no change) order not PENDING_PAYMENT (cancelled / expired) → LATE:
      otherwise → payment SUCCESS (transaction_ref, paid_at)      payment SUCCESS + refund_required,
                  + PaymentSucceededEvent ─► order PAID             admins notified, order stays CANCELLED
                                             (same transaction)
COD: payment stays PENDING until the order is DELIVERED → SUCCESS ("cash collected").
Refund (admin): PAID / PROCESSING → CANCELLED + stock back + payment REFUNDED;
                DELIVERED → REFUNDED (+ stock back only if `restock`) + payment REFUNDED.
```

---

## Step 0 — shared contract ✅ done (on `main`)

Already built and tested. Every agent builds on these files and **must not change them**.

**Things to know before you start:**
- **The SDK needs an expiration timestamp** for a KHQR with an amount (`IndividualInfo.setExpirationTimestamp`,
  epoch **milliseconds**), else it returns `errorCode=45` "Expiration timestamp is required for dynamic KHQR".
  Check `getKHQRStatus().getCode() == 0`; the code is in `getData().getQr()`, the md5 in `getData().getMd5()`.
  The SDK's OkHttp and commons-validator are excluded in `pom.xml` (unused; the latter pulled a vulnerable
  commons-beanutils) — `Step0PaymentSchemaTest` proves generate + verify still work offline.
- **Payment classes moved** to `com.psar.payment` (`entity.Payment`, `PaymentStatus`, `PaymentType`, its own
  `PaymentMethod` copy, `repository.PaymentRepository`). The order module only calls `PaymentService`
  (`createForOrder`, `cancelPending`, `findForOrder` → `Optional<PaymentView>`); methods cross as strings.
- **No circular dependency:** `PaymentService` must not depend on order services. `PaymentProcessor` may use
  `OrderLookupService` (to tell a LATE payment), and tells the order module through `PaymentSucceededEvent`.
- **Uniqueness:** `(provider, provider_ref)` UNIQUE, `qr_md5` UNIQUE, `transaction_ref` UNIQUE.
  `PaymentRepository.lockByProviderRef(provider, ref)` (FOR UPDATE), `lockByOrderId`, `findIdsToCheck(...)`.
- `BakongClient.findPaid(md5)` returns a `GatewayPayment` (transaction ref, amount, currency, paidAt, raw).
- **Fixtures:** `PaymentFixtures` (bean in every `IntegrationTest`): `khqrOrder(Map<Product, qty>)` — a
  PENDING_PAYMENT KHQR order whose payment always has a provider ref (a test md5 until Agent A's QR lands),
  `payment(order)`, `payAtBakong(order)` (the fake Bakong now says "paid", returns the bank ref).
- Postgres keeps timestamps in **microseconds**: compare `Instant`s read back from the DB with a tolerance.

### 1. `V5__payments_khqr.sql`
> Migrations shift again: `architecture.md` planned promotions as V5; they become V6 … reviews V11.

- `payments`: `provider` (`BAKONG_KHQR`), `provider_ref` (UNIQUE with `provider`; KHQR: = `qr_md5`), `redirect_url` NULL,
  `qr_md5` UNIQUE, `refund_required` BOOLEAN default false, `refunded_at`, `refund_reason`,
  `refunded_by` → users, `last_checked_at`, `check_attempts` INT default 0. Index `(status, created_at)` exists.
- `payment_events` (append-only): `id`, `payment_id` NULL → payments, `source` (`CHECK_JOB`/`WEBHOOK`/
  `SIMULATED`), `transaction_ref` NULL, `qr_md5` NULL, `amount` NULL, `currency` NULL, `payload` JSONB,
  `signature_valid` BOOLEAN NULL, `outcome` (`APPLIED`/`DUPLICATE`/`LATE`/`AMOUNT_MISMATCH`/`UNKNOWN_PAYMENT`/
  `REJECTED`), `received_at`. Index `(payment_id)`, `(received_at)`.

### 2. Payments move to the `payment` module
`Payment`, `PaymentStatus`, `PaymentType`, `PaymentRepository` move from `order` to `payment` (+ new
`PaymentEvent`, `PaymentEventSource`, `PaymentOutcome`, `PaymentEventRepository`). The order module no longer
touches payment tables; it calls **`PaymentService`**:
- `createForOrder(orderId, orderNo, method, amount, expiresAt)` → `PaymentView` — checkout calls it (Step 0
  version: PENDING row, no QR; Agent A adds the QR).
- `cancelPending(orderId)` — the order workflow calls it on cancel (replaces Phase 4's direct repository use).
- `forOrder(orderId)` → `PaymentView` (status, method, amount, QR fields, `refundRequired`).
- Payment → order goes through an **event**, not a call (no circular dependency):
  `payment.event.PaymentSucceededEvent(paymentId, orderId, paidAt)`, published **inside** the confirming
  transaction; the order module handles it with a plain `@EventListener` (same transaction, so "payment
  SUCCESS" and "order PAID" commit together). Plus `LatePaymentEvent(paymentId, orderId)` for notifications.

### 3. Payment gateways (one interface, Bakong KHQR first)
So ABA PayWay and cards can plug in later (their own phase, once there is a PayWay merchant account) without
touching checkout, orders or confirmation:

```java
public interface PaymentGateway {
    PaymentProvider provider();                                   // BAKONG_KHQR now; ABA_PAYWAY later
    boolean supports(String paymentMethod);                       // "KHQR" now; "CARD", "ABA_PAY" later
    GatewayCharge createCharge(ChargeRequest request);            // QR string and/or redirect URL + provider ref
    Optional<GatewayPayment> checkStatus(String providerRef);     // used by the check job
    Optional<PaymentConfirmation> parseCallback(Map<String, String> headers, byte[] body); // webhook; verifies the signature
}
```
- `payments.provider` (`BAKONG_KHQR`) and `payments.provider_ref` (for KHQR = the QR's md5) are generic, so a
  PayWay transaction id fits in the same columns. `qr_string` stays for KHQR; `redirect_url` is added for hosted
  checkouts like PayWay.
- **`BakongKhqrGateway`** (Step 0, thin) delegates: `createCharge` → `KhqrService` (Agent A), `checkStatus` →
  `BakongClient` (Agent B), `parseCallback` → `KhqrWebhookParser` (the HMAC webhook format, Agent B).
- **`PaymentGateways.forMethod(method)`** picks the gateway. `PaymentService`, the check job and the webhook only
  ever talk to `PaymentGateway`, never to Bakong classes directly.
- Webhook path is per provider: `POST /api/payments/webhook/{provider}` (`bakong-khqr` now).

### 4. Contracts
- `payment.service.khqr.KhqrService.generate(KhqrRequest(orderNo, amount, expiresAt))` → `KhqrCode(qr, md5)`
  — Agent A. Step 0 version: returns null (no QR yet).
- `payment.service.confirm.PaymentProcessor.confirm(PaymentConfirmation(source, qrMd5, transactionRef, amount,
  currency, paidAt, payload, signatureValid))` → `PaymentOutcome` — Agent B. Step 0 version: records the event
  as `REJECTED` and changes nothing.
- `payment.service.bakong.BakongClient.findPaid(String qrMd5)` → `Optional<GatewayPayment>` — interface;
  Step 0 ships **`FakeBakongClient`** (`app.payment.bakong.mode=fake`, the default in dev and tests) that answers
  "paid" for md5s marked with `markPaid(md5, amount)`. Agent B adds the real HTTP client (`mode=bakong`).
- Config `app.payment.khqr.*` (account id, merchant name / city, currency USD) and `app.payment.bakong.*`
  (mode, base URL, token from `BAKONG_API_TOKEN`), `app.payment.webhook.secret` (`PAYMENT_WEBHOOK_SECRET`),
  `app.payment.simulate.enabled` (true only in `dev`).
- New `NotificationType`s: `ORDER_PAID` (customer), `PAYMENT_LATE` (admins); `AuditEntity.PAYMENT`.

### 5. Test support
`PaymentFixtures` (see above) and `payment/service/Step0PaymentSchemaTest`.

---

## File ownership

| File / area | Owner |
|---|---|
| Step 0 files (migration, moved / new entities, repositories, `PaymentService` signatures, events, contracts, `FakeBakongClient`, config classes, fixtures) and the Phase 4 code they change (checkout calling `PaymentService`, workflow calling `cancelPending`) | **lead only** |
| Body of `KhqrService` (used by `BakongKhqrGateway.createCharge`); `CustomerPaymentController` | Agent A |
| Body of `PaymentProcessor`; `BakongHttpClient`; body of `KhqrWebhookParser` (used by `BakongKhqrGateway.parseCallback`); `PaymentCheckJob`; `PaymentWebhookController`; `PaymentSimulationController` | Agent B |
| `order` listener for `PaymentSucceededEvent`; COD collected at DELIVERED; `RefundService` + endpoints; `AdminPaymentController`; `notification/listener/PaymentNotificationListener` | Agent C |
| `SecurityConfig` (webhook / simulate paths open), `common/exception/**`, Flyway | **lead only** |

---

## Agent A — KHQR codes

### Build
- **`KhqrService.generate`** with the official NBC SDK (`kh.gov.nbc.bakong_khqr:sdk-java`): individual KHQR for
  the configured Bakong account, merchant name / city, **USD**, amount = order total (BigDecimal → the SDK's
  `Double` only at the boundary, 2 decimals), bill number = order number, expiration = the order's `expires_at`.
  SDK errors → a clear 500-level error logged with the order number (never a half-saved checkout).
- **`PaymentService.createForOrder`** already asks the gateway (`createCharge`) in Step 0; once `KhqrService`
  returns a code it stores `qr_string`, `qr_md5` / `provider_ref`, `expires_at`. COD: no gateway, no QR.
- **Customer endpoint** `GET /api/orders/{publicId}/payment` (own order only, 404 otherwise): `status`, `method`,
  `amount`, `currency`, `expiresAt`, `qrString` (only while PENDING and not expired), `paidAt`. Never the md5,
  the transaction ref or the raw payload.
- Checkout response (Phase 4) gains `payment` with the same fields, so the UI can show the QR straight away.

### Tests (at least)
- `generate_validOrder_qrPassesSdkVerifyAndDecodesToAmountAndBillNumber` (use `BakongKHQR.verify` / `decode`)
- `checkout_khqr_returnsQrAndStoresMd5`, `checkout_cod_noQr`
- `payment_otherCustomersOrder_returns404`, `payment_expired_hidesQr`, `payment_neverShowsMd5OrRef`
- `generate_amountRounding_twoDecimals` (e.g. 10.005 is never sent as 10.004999…)

---

## Agent B — Payment confirmation

### Build
- **`PaymentProcessor.confirm`** — one transaction; lock the payment by `qr_md5` (`SELECT … FOR UPDATE`);
  always write a `payment_events` row; outcomes exactly as in the flow above. `transaction_ref` is UNIQUE, so the
  same bank transaction can never pay two orders. On APPLIED publish `PaymentSucceededEvent`; on LATE set
  `refund_required` and publish `LatePaymentEvent`.
- **`BakongHttpClient`** (`mode=bakong`): Spring `RestClient`, bearer token, Bakong "check transaction by md5"
  API, short timeouts; a network error or "not found" means "not paid yet", never an exception for the job.
  **Verify the exact endpoint and response against the Bakong Open API docs before coding** and note the link
  in the class Javadoc.
- **`PaymentCheckJob`** (`@Scheduled` every minute + `@SchedulerLock`), through `PaymentGateway.checkStatus`: PENDING gateway payments created ≥ 1 minute
  ago and not checked in the last minute, **including those expired in the last 30 minutes** (catches late
  payments); for each: `checkStatus(providerRef)` → `confirm(CHECK_JOB, …)`; update `last_checked_at`, `check_attempts`. Each
  payment in its own transaction.
- **`PaymentWebhookController`** `POST /api/payments/webhook/{provider}` → `gateway.parseCallback(...)`. For
  `bakong-khqr`: header `X-Signature` = hex HMAC-SHA256 of the
  raw body with `PAYMENT_WEBHOOK_SECRET`, constant-time compare. Bad signature → 401 and an event row with
  `signature_valid = false`, `outcome = REJECTED`. Valid → `confirm(WEBHOOK, …)` → **200 for every outcome**
  (so the sender doesn't retry a duplicate forever). Body size limit 64 KB.
- **`PaymentSimulationController`** `POST /api/payments/{publicId}/simulate` — only when
  `app.payment.simulate.enabled=true` (dev / demo); the customer who owns the order or an ADMIN; marks the md5 paid
  at the fake Bakong and runs `confirm(SIMULATED, …)` right away. 404 when disabled.

### Tests (at least)
- `confirm_pendingPayment_successAndOrderPaid`, `confirm_twice_secondIsDuplicateNoChange`
- `confirm_concurrentWebhookAndJob_appliedExactlyOnce` (two threads, same md5)
- `confirm_amountMismatch_paymentUntouched`, `confirm_unknownMd5_eventRecorded`
- `confirm_afterOrderExpired_lateRefundRequired`
- `checkJob_paidAtBakong_confirms`, `checkJob_notPaid_onlyCounts`, `checkJob_bakongDown_noException`
- `webhook_badSignature_401`, `webhook_duplicate_200NoChange`
- `simulate_disabled_404`, `simulate_otherCustomersOrder_404`

---

## Agent C — Order side, COD & refunds

### Build
- **Order listener** (`order` module, `@EventListener`, same transaction) for `PaymentSucceededEvent`: lock the
  order, `PENDING_PAYMENT → PAID` through the Phase 4 workflow (history row, `changed_by` null, note "Paid by
  KHQR"), `OrderStatusChangedEvent`. Not PENDING_PAYMENT any more → do nothing (the processor already treated it
  as LATE).
- **Notifications** (`notification/listener/PaymentNotificationListener`, AFTER_COMMIT): order PAID → staff
  `NEW_ORDER` (the KHQR counterpart of Phase 4's COD notification) and customer `ORDER_PAID`; late payment →
  ADMIN/MANAGER `PAYMENT_LATE` ("refund by hand").
- **COD:** when a COD order becomes DELIVERED, its payment → SUCCESS, `paid_at` = now ("cash collected").
- **`RefundService`** + `POST /api/admin/orders/{publicId}/refund` (`reason`, `restock` for delivered orders):
  - PAID / PROCESSING (KHQR): order → CANCELLED, stock released, payment → REFUNDED (`refunded_at/_by`,
    `refund_reason`).
  - DELIVERED: order → REFUNDED, payment → REFUNDED; stock back (`StockService.release`, reason RELEASE) only if
    `restock = true`.
  - Anything else → 409. Twice → 409, stock back once. Audit row (`AuditEntity.PAYMENT`). Customer notified
    (`ORDER_STATUS_CHANGED` / cancelled).
- **Late payments:** `POST /api/admin/payments/{publicId}/refunded` marks a `refund_required` payment REFUNDED
  after the money was returned by hand.
- **`AdminPaymentController`** (ADMIN/MANAGER): `GET /api/admin/payments?status=&method=&refundRequired=&from=&to=`,
  `GET /api/admin/payments/{publicId}` with its `payment_events` (payload included — admins only).

### Tests (at least)
- `paymentSucceeded_pendingOrder_paidWithHistory`, `paymentSucceeded_staffAndCustomerNotified`
- `codDelivered_paymentSuccess`
- `refund_paidOrder_cancelledStockBackPaymentRefunded`, `refund_deliveredWithoutRestock_stockUnchanged`,
  `refund_twice_409`, `refund_staff_403`
- `latePayment_adminsNotified_markRefunded`
- `adminPayments_filterRefundRequired`

---

## Rules for every agent

Same as Task 4: follow `CLAUDE.md`; constructor injection; thin controllers; `@Transactional` on writing service
methods; DTO records only; lists paginated (max 100); test names `methodName_condition_expectedResult`;
integration tests extend `IntegrationTest`. Money is `BigDecimal` scale 2; compare amounts with `compareTo`.
**Never log or return** the webhook secret, the Bakong token or a full raw payload to customers. Stock only
through `StockService`. No `Co-Authored-By` lines. Don't touch Flyway — ask the lead. Before finishing:
`cd backend && ./mvnw test` passes; endpoints look right in Swagger.

## Merge order

1. Step 0 → `main`.
2. Agents work in parallel from that `main`.
3. Merge **B → C → A** (B's processor and C's PAID listener meet through the Step 0 event; A only adds the QR).
   After each merge, the next branch rebases on `main` and re-runs `./mvnw test`.
4. After all three, one end-to-end test on `main`: KHQR checkout returns a QR → simulate payment → order PAID,
   staff + customer notified → the same webhook again is a DUPLICATE → refund → CANCELLED, stock back, payment
   REFUNDED; second order expires, then the customer pays late → LATE, admins notified, marked refunded by hand;
   COD order delivered → payment SUCCESS.

## Done when

- [x] Step 0 merged
- [ ] Agent B merged
- [ ] Agent C merged
- [ ] Agent A merged
- [ ] End-to-end test passes; `CLAUDE.md` "Current phase" → Phase 5 done (MVP)
