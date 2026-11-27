# Task 3 — Phase 3: Audit logs & in-app notifications (2 agents in parallel)

Record who changed what (audit logs, admin only), and give every user an in-app notification inbox,
with the first real notification: **low stock → managers**.

Already done (on `main`): Phase 1 (users, auth) and Phase 2 (catalog, inventory, Cloudinary images).
Same workflow as `task/task2.md`.

Read first: `CLAUDE.md` (especially **Events and background work**), `PRD.md` §2.1 (permissions),
§3.11 Notifications, §3.14 Audit logs, `architecture.md` §4.2 (System tables).

**Not in this phase:** Telegram and email channels (Phase 9, P1/P2), `outbox_events` and `shedlock`
(Phase 9: before RabbitMQ we use `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`),
order / points / subscription / promotion notifications (their phases — this phase builds the inbox they
will write to), `AI_ACTION` audit entries (Phase 11 — the action exists, nothing writes it yet),
frontend screens.

---

## How the work is split

| | Agent A — Audit logs | Agent B — Notifications |
|---|---|---|
| Branch | `task3/agent-a-audit` | `task3/agent-b-notifications` |
| Services | `AuditService` (full), audit calls in existing services | `NotificationService` (full), `LowStockNotificationListener` |
| Controllers | `AdminAuditLogController` | `NotificationController` |

**Step 0** (lead, on `main`, before the agents start) creates the tables, entities, the two service entry
points and the low-stock event, so neither agent waits for the other.

---

## Permissions (PRD §2.1)

| Feature | ADMIN | MANAGER | STAFF | CUSTOMER |
|---|---|---|---|---|
| Read audit logs | ✅ | ❌ | ❌ | ❌ |
| Own notifications (list, unread count, mark read) | ✅ | ✅ | ✅ | ✅ |
| Receives low-stock notifications | ✅ | ✅ | ❌ | ❌ |

---

## Step 0 — shared contract (lead, on `main`)

Every agent builds on these files and **must not change them**.

### 1. `V3__audit_and_notifications.sql`

> **Numbering:** `architecture.md` planned these tables as `V7__system.sql`, but Flyway applies versions in
> order and refuses a lower version after a higher one. Phase 3 comes before orders, so this is **V3**, and
> the later files move up by one (orders V4 … reviews V10). Step 0 updates `architecture.md` §4.5.

| Table | Columns |
|---|---|
| `audit_logs` | `id` BIGSERIAL, `public_id` UUID UNIQUE, `user_id` → users NULL (null = system / anonymous), `action` VARCHAR(20) (`CREATE/UPDATE/DELETE/LOGIN/LOGIN_FAILED/AI_ACTION`), `entity` VARCHAR(50) (e.g. `PRODUCT`), `entity_id` VARCHAR(100) (the **public** id / slug / PO number, never the internal id), `details` JSONB, `ip_address` VARCHAR(45) NULL, `created_at`. Indexes: `(created_at DESC)`, `(entity, entity_id)`, `(user_id, created_at DESC)` |
| `notifications` | `id` BIGSERIAL, `public_id` UUID UNIQUE, `user_id` → users ON DELETE CASCADE, `type` VARCHAR(40) (`LOW_STOCK` now; order/points/… types later), `title` VARCHAR(150), `message` VARCHAR(1000), `link` VARCHAR(255) NULL (frontend path, e.g. `/admin/inventory/low-stock`), `channel` VARCHAR(20) (`APP` now), `is_read` BOOLEAN default false, `read_at` NULL, `created_at`. Index: `(user_id, is_read, created_at DESC)` |

Audit rows are **insert-only**: no update or delete endpoint, and the entity has no setters after creation.

### 2. Entities + enums
`AuditLog`, `AuditAction`; `Notification`, `NotificationType`, `NotificationChannel`. Repositories with
`JpaSpecificationExecutor` for the filtered lists.

### 3. Cross-module entry points (the only way other modules touch these two)
- **`AuditService.record(AuditAction action, String entity, String entityId, Map<String, Object> details)`**
  - `Propagation.MANDATORY`: the audit row commits or rolls back **with the change it describes**.
  - The actor (`user_id`) and IP come from the current request (`SecurityContext` / `RequestContextHolder`),
    so callers don't pass them; null outside a request.
  - Step 0 ships it working but called nowhere; Agent A adds the calls.
- **`AuditService.recordLoginFailed(String email)`**: `Propagation.REQUIRES_NEW`, because a failed login
  rolls back its own transaction. Stores the email in `details`, never the password.
- **`NotificationService.notifyUsers(Collection<Long> userIds, NotificationType type, String title,
  String message, String link)`** and **`notifyRoles(Set<Role> roles, …)`** (every **active** user with one of
  those roles). Later phases (orders, points, …) call these.
- **`StockLowEvent(Long productId)`**: `StockService` publishes it when a stock change takes a product
  **from above `min_stock` to at or below it** (crossing only, so repeated sales below the minimum don't
  spam). `ProductStockService.decrease` / the adjust path return the new balance and `min_stock` for this.
  The event carries only the id (consumers reload, per `CLAUDE.md`).

### 4. Test support
`AuditFixtures` / `NotificationFixtures` beans in `IntegrationTest` (create a user with a role, read the
audit rows for an entity, read a user's notifications), plus `Step0SystemSchemaTest` (insert-only audit row,
one-row-per-user notifications, `StockLowEvent` fires only on crossing).

---

## File ownership

| File / area | Owner |
|---|---|
| Step 0 files (migration, entities, enums, repositories, `record` / `recordLoginFailed`, `notifyUsers` / `notifyRoles`, `StockLowEvent` and its publishing in `StockService`, fixtures) | **lead only** |
| Rest of `audit/**`; the one-line `auditService.record(...)` calls inside existing services (user, delivery, catalog, inventory) | Agent A |
| Rest of `notification/**`, `LowStockNotificationListener` | Agent B |
| `SecurityConfig`, `common/exception/**`, `common/handler/**`, Flyway | **nobody** (lead only) |

Agent A only **adds** audit calls to existing services — no other changes to their logic. If a call needs
data the method doesn't have, ask the lead.

---

## Agent A — Audit logs

### Build
- **What gets logged** (PRD §3.14 "important records"):

  | Entity | Actions | Where |
  |---|---|---|
  | `USER` | CREATE (staff), UPDATE (role, status) | `UserAdminService` |
  | `AUTH` | LOGIN, LOGIN_FAILED | `AuthService.login` |
  | `DELIVERY_COMPANY` | CREATE, UPDATE, DELETE | `DeliveryCompanyService` |
  | `CATEGORY`, `PRODUCT`, `SUPPLIER` | CREATE, UPDATE, DELETE | their services |
  | `PRODUCT_IMAGE` | CREATE, DELETE | `ProductImageService` |
  | `PURCHASE_ORDER` | CREATE, UPDATE (`received` / `cancelled`) | `PurchaseOrderService` |
  | `STOCK_ADJUSTMENT` | CREATE | `StockService.adjust` |

  **Not logged:** a customer's own profile and addresses (their own data, high volume), registration,
  logout, image reorder / set-main, stock changes from purchase orders (already in `stock_movements`).
- **`details`**: CREATE → the main fields; UPDATE → only the changed fields as `{"field": {"from": …, "to": …}}`
  (nothing changed → no row); DELETE → name / SKU so the row still reads well. **Never** passwords, password
  hashes, tokens or the storage key. Cost price is fine (audit logs are admin only).
- **`AdminAuditLogController`**: `GET /api/admin/audit-logs?user=&action=&entity=&entityId=&from=&to=&page=&size=`
  (ADMIN only, newest first, max 100 per page) and `GET /api/admin/audit-logs/{publicId}`. The response shows
  the actor's `publicId`, email and name — never the internal user id.

### Tests (at least)
- `createProduct_writesCreateAuditRow`, `updateProduct_writesOnlyChangedFields`, `updateProduct_nothingChanged_writesNoRow`
- `createProduct_failsValidation_writesNoAuditRow` (rolled back with the change)
- `login_success_writesLoginRow`, `login_wrongPassword_writesLoginFailedRowWithoutPassword`
- `changeRole_writesFromAndTo`, `adjustStock_writesAuditRowAndMovement`
- `auditLogs_manager_returns403`, `auditLogs_filters_byEntityAndDate`

---

## Agent B — Notifications

### Build
- **`NotificationService`** (keep the Step 0 methods): list mine (`unread` filter, newest first, paginated),
  unread count, mark one read, mark all read. Every lookup is `findByPublicIdAndUserId` — another user's
  notification is a **404**.
- **`LowStockNotificationListener`**: `@TransactionalEventListener(AFTER_COMMIT)` on `StockLowEvent`, then a
  **new transaction** (`REQUIRES_NEW`): reload the product (via `ProductStockService` / catalog service, not
  its repository), skip it if it's no longer at or below the minimum or is inactive, then
  `notifyRoles({ADMIN, MANAGER}, LOW_STOCK, "Low stock: {name}", "{name} ({sku}) has {qty} left (minimum {min}).",
  "/admin/inventory/low-stock")`. A failure is logged and never affects the stock change (it already committed).
- **`NotificationController`** (any logged-in user):

  | Method | Path |
  |---|---|
  | GET | `/api/notifications?unread=&page=&size=` |
  | GET | `/api/notifications/unread-count` → `{ "count": n }` |
  | PATCH | `/api/notifications/{publicId}/read` |
  | POST | `/api/notifications/read-all` → `{ "updated": n }` |

- Response: `publicId`, `type`, `title`, `message`, `link`, `read`, `createdAt` — no user id.

### Tests (at least)
- `adjustStock_crossesMinimum_notifiesActiveManagersAndAdmins` (not staff, not customers, not inactive users)
- `adjustStock_alreadyBelowMinimum_noNewNotification`
- `adjustStock_rolledBack_noNotification`
- `markRead_otherUsersNotification_returns404`
- `readAll_marksOnlyMine_returnsCount`, `unreadCount_afterMarkRead_decreases`
- `notifications_noLogin_returns401`

---

## Rules for every agent

Same as Task 2: follow `CLAUDE.md`; constructor injection; thin controllers; `@Transactional` on writing
service methods; DTO records only; lists paginated (max 100) with a sort allowlist; test names
`methodName_condition_expectedResult`; integration tests extend `IntegrationTest` and use unique
names/emails. No `Co-Authored-By` lines. Don't touch Flyway — need a schema change? Ask the lead.
Before finishing: `cd backend && ./mvnw test` passes; endpoints look right in Swagger.

## Merge order

1. Step 0 → `main`.
2. Agents work in parallel from that `main`.
3. Merge **B → A** (A touches many existing services, so it goes last and rebases once).
   After each merge, the next branch rebases on `main` and re-runs `./mvnw test`.
4. After both, one end-to-end test on `main`: manager logs in (LOGIN row) → creates a product with
   `minStock` 10 → receives a PO of 12 → damage adjustment −5 crosses the minimum → the manager and an admin
   each get one LOW_STOCK notification, a staff user gets none → manager marks it read (unread count 0) →
   admin's audit log shows LOGIN, product CREATE, PO CREATE/received and the STOCK_ADJUSTMENT, and a manager
   gets 403 on the audit log.

## Done when

- [ ] Step 0 merged
- [ ] Agent B merged
- [ ] Agent A merged
- [ ] End-to-end test passes; `CLAUDE.md` "Current phase" → Phase 3 done
