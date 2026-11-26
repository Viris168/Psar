# CLAUDE.md

Guidance for AI coding assistants working in this repository.

## Project

**Psar** — an online store for a Cambodian shop: storefront, inventory, promotions, points, subscriptions and an AI chatbot.
Solo portfolio project built phase by phase. Read before large changes:

- `PRD.md` — what to build and why
- `architecture.md` — full design, data model, flows, queues, jobs
- `essential-architecture.md` — the rules on one page

## Repository layout

```
/backend     Spring Boot 3, Java 21, Maven — modular monolith (package com.psar)
/frontend    React + TypeScript + Vite, Tailwind, shadcn/ui
/docker-compose.yml   postgres (pgvector), rabbitmq, minio, backend, frontend
```

Backend modules: `user, delivery, catalog, inventory, order, payment, promotion, loyalty, subscription, notification, audit, ai, messaging, common`.
Each module uses: `controller/ service/ repository/ entity/ dto/(request|response) mapper/`.

## Commands

```bash
# Infrastructure
docker compose up -d postgres rabbitmq minio
docker compose down -v                 # reset local data (dev only)

# Backend
cd backend
mvn spring-boot:run                    # or ./mvnw after: mvn -N wrapper:wrapper
mvn test
mvn verify                             # includes ArchUnit + Testcontainers integration tests

# Frontend
cd frontend
npm install
npm run dev
npm run build
npm run lint
```

Swagger UI: `http://localhost:8080/swagger-ui.html` · RabbitMQ UI: `http://localhost:15672`

## Current phase

> Update this line as work progresses.

**Phase 0 — Setup.** Next: Phase 1 (users, auth, profiles, addresses, delivery companies).

Phases: 0 Setup → 1 Users → 2 Catalog & inventory → 3 Audit & notifications → 4 Orders & checkout → 5 Payments (MVP) → 6 Promotions → 7 Points → 8 Subscription → 9 RabbitMQ & jobs → 10 Dashboard → 11 AI chatbot → 12 Tests → 13 Launch.
Only build what the current phase needs. Do not add tables or features from later phases early.

## Non-negotiable rules

### Data
- Money: `NUMERIC(12,2)` in SQL, `BigDecimal` in Java (`RoundingMode.HALF_UP`, scale 2). Never `double`/`float`.
- Primary keys: `BIGSERIAL id` (internal). Public identifiers: `public_id UUID` or `slug`. Never expose `id` in APIs.
- Enums stored as strings: `@Enumerated(EnumType.STRING)`.
- Schema changes only through **new** Flyway files `V{n}__description.sql`. **Never edit an existing migration.**
- JPA `ddl-auto` must stay `validate`.
- Orders store snapshots (address, product name, unit price, promotion name, company name).

### Security
- Every user-owned lookup includes the current user: `findByPublicIdAndUserId(...)`; missing → 404.
- Every admin/staff endpoint has `@PreAuthorize`.
- Return DTOs only. Never serialize entities. Never return `cost_price` to customers, never return `password_hash`.
- Recalculate all prices and totals on the backend; ignore client-sent prices.
- No secrets in code or `application.yml`; use environment variables.

### Stock and money flows
- Stock changes use atomic SQL (`UPDATE ... SET quantity = quantity - :q WHERE id = :id AND quantity >= :q`) and check the updated row count.
- Every stock change writes a `stock_movements` row in the same `@Transactional` method.
- Checkout is one transaction. KHQR orders: `PENDING_PAYMENT` with `expires_at = now + 15 min`.
- Payment processing is idempotent (unique `transaction_ref`; skip if already SUCCESS).
- Points are awarded only at `DELIVERED`.
- Pricing order: item promotion → one coupon → one reward voucher → delivery fee → total ≥ 0. Keep it in `PricingService` as pure, unit-tested logic.

### Events and background work
- Publish domain events through the outbox (`outbox_events`) inside the business transaction. Before Phase 9, use `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)`.
- Event payloads contain IDs only; consumers reload data.
- Consumers must be idempotent. Retry 3× with backoff, then dead-letter. Never rely on Spring's default infinite requeue.
- `@Scheduled` jobs use ShedLock, only find work and publish events.
- Keep synchronous: auth, cart, catalog, checkout, admin CRUD, AI chat streaming.

### Modules
- A module may call another module's **service** or react to its **events**, never use its repository or entities directly.
- Shared code goes in `common` only if at least two modules need it.

### AI
- Spring AI tools call existing services as the current user and re-check role + ownership.
- AI write actions create `ai_pending_actions`; execution only after user confirmation; log `AI_ACTION` to `audit_logs`.
- AI API keys stay on the backend.

## Code style

### Backend
- Constructor injection (`@RequiredArgsConstructor`), no field injection.
- Controllers are thin: validate (`@Valid`), call one service method, return DTO.
- Business logic in services; `@Transactional` on service methods that write.
- MapStruct for entity ↔ DTO. Request/response DTOs as Java `record`s.
- Errors: throw domain exceptions (`NotFoundException`, `ConflictException`, `BusinessRuleException`); handled in `GlobalExceptionHandler` → `{ "code", "message", "details", "timestamp" }`.
- Lists are always paginated (`Pageable`, max page size 100).
- REST paths: `/api/{resource}` for customers, `/api/admin/{resource}` for staff/admin. Plural nouns, kebab-case.
- Names: `XxxController`, `XxxService`, `XxxRepository`, `XxxRequest`, `XxxResponse`, `XxxEvent`.

### Frontend
- TypeScript strict. Server state with TanStack Query; no fetching in `useEffect`.
- API calls only in `src/api/`. Feature code in `src/features/{feature}`.
- All user-facing text through i18n (`en`, `km`).
- Role-guarded routes for `/admin`.
- Never put API keys or secrets in the frontend.

## Testing

- Unit tests (JUnit 5 + Mockito) for: `PricingService`, promotions, points, stock reservation, status transitions.
- Integration tests (Spring Boot Test + Testcontainers PostgreSQL/RabbitMQ) for main APIs.
- Must exist before launch: concurrency test — 10 buyers, 3 items left → exactly 3 orders succeed.
- Test names: `methodName_condition_expectedResult`.
- Run `mvn test` before finishing any backend change.

## Definition of done (per feature)

- [ ] Backend + frontend working
- [ ] Validation, role checks and ownership checks in place
- [ ] Flyway migration added (if schema changed)
- [ ] At least one test for the business rule
- [ ] Swagger shows the endpoint correctly
- [ ] No secrets committed; `mvn test` and `npm run build` pass

## Do not

- Do not add microservices, Kubernetes, Redis or Elasticsearch (future scaling only).
- Do not edit old migrations or switch `ddl-auto` away from `validate`.
- Do not return entities or trust client prices.
- Do not reduce stock outside the atomic update + stock movement pattern.
- Do not call external services (Telegram, Gemini, Bakong) inside the checkout transaction.
- Do not commit `.env` files or credentials.

## Glossary

| Term | Meaning |
|---|---|
| KHQR | Cambodia's national QR payment standard (Bakong) |
| CITY / PROVINCE | Delivery types: Phnom Penh own driver vs courier company |
| Khan / Sangkat | District / commune in Phnom Penh |
| Snapshot | Value copied into the order at checkout so history never changes |
| Outbox | Table of events written in the same transaction, published to RabbitMQ afterwards |
| Member | User with an ACTIVE subscription |
