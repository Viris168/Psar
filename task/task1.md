# Task 1 — Phase 1 services (3 agents in parallel)

Build the service layer and REST endpoints for Phase 1: auth, profile, addresses,
admin user management and delivery companies.

Already done (on `main`): entities, repositories, DTOs, MapStruct mappers, exceptions,
`GlobalExceptionHandler`, `SecurityErrorHandler`.

Read first: `CLAUDE.md` (rules), `PRD.md` §2–3.2 (roles, accounts, addresses).

---

## How the work is split

| | Agent 1 — Auth & security | Agent 2 — Profile & addresses | Agent 3 — Admin & delivery |
|---|---|---|---|
| Branch | `task1/agent-1-auth` | `task1/agent-2-profile-address` | `task1/agent-3-admin-delivery` |
| Services | `JwtService`, `AuthService` | `ProfileService`, `AddressService` | `UserAdminService`, `DeliveryCompanyService` (full) |
| Controllers | `AuthController` | `ProfileController`, `AddressController` | `AdminUserController`, `AdminDeliveryCompanyController`, `DeliveryCompanyController` |
| Extra | `JwtAuthenticationFilter`, `SecurityConfig` | — | new admin DTOs |

The agents never wait for each other: **Step 0** creates the small shared pieces
(the logged-in-user type and a test helper) before anyone starts. Agents 2 and 3 test
with a fake logged-in user, so they don't need Agent 1's JWT code to exist.

---

## Step 0 — shared contract ✅ done (on `main`)

Already built and tested. Every agent builds on these files and **must not change them**.

1. **`common/security/AuthenticatedUser.java`** — who is calling. Lives in `common` because
   every module's controllers need it.
   ```java
   public record AuthenticatedUser(Long userId, UUID publicId, String email, String role) {
       public boolean hasRole(String r) { return role.equals(r); }
   }
   ```
   `role` is a `String` (`"ADMIN"`, `"CUSTOMER"`…): `common` may not import `user.entity.Role`
   (ModuleBoundaryTest). Spring authorities are `ROLE_` + role, so `@PreAuthorize("hasRole('ADMIN')")` works.

   Controllers get it with `@AuthenticationPrincipal AuthenticatedUser me`.

2. **`delivery/service/DeliveryCompanyService.java`** — created with just one method, so
   Agent 2 can call it while Agent 3 fills in the rest:
   ```java
   /** Throws BusinessRuleException if the company is missing or inactive. */
   public void assertActive(Long deliveryCompanyId)
   ```

3. **`src/test/java/com/psar/support/TestAuth.java`** — fake login for MockMvc tests:
   ```java
   mockMvc.perform(get("/api/me").with(TestAuth.as(user)))   // user = saved User entity
   mockMvc.perform(get("/api/admin/users").with(TestAuth.asRole(1L, "ADMIN")))
   ```
   Builds an `AuthenticatedUser` and sets it via `SecurityMockMvcRequestPostProcessors.authentication(...)`.

4. **`src/test/java/com/psar/support/IntegrationTest.java`** — base class:
   `@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)`,
   so all agents share one Spring context and one Postgres container.
   Gives you `mockMvc`, `objectMapper` and `json(body)`. Tests don't roll back: use unique emails.
   Don't add `@MockitoBean`/`@TestPropertySource` to a subclass — it starts a second context.

Existing tests that show the pattern: `common/security/TestAuthTest` (TestAuth + 401/403 JSON)
and `delivery/service/DeliveryCompanyServiceTest` (`assertActive`).

---

## File ownership (prevents merge conflicts)

Only the owner edits a file. Everything else on `main` is read-only for all agents.
If you need a change in a file you don't own, write it under "Notes for the lead" in your PR.

| File / area | Owner |
|---|---|
| `common/security/SecurityConfig.java`, `application*.yml` | Agent 1 |
| `user/security/**` (new), `RefreshTokenRepository` | Agent 1 |
| `UserProfileRepository`, `AddressRepository` | Agent 2 |
| `UserRepository`, `delivery/**` (except `assertActive` signature) | Agent 3 |
| `common/exception/**`, `common/handler/**`, entities, mappers, existing DTOs, Flyway | **nobody** (lead only) |

New files in your own area are always fine. Use the existing `ErrorCode` values; don't add new ones.

---

## Agent 1 — Auth & security

**Goal:** register, log in, refresh, log out, change password; every request after login is authenticated by JWT.

### Build
- **`user/security/JwtService`**
  - `String createAccessToken(User)` — HS256, `sub` = `publicId`, claim `role`, TTL `app.jwt.access-token-ttl` (15 min).
  - `UUID parseAccessToken(String)` — returns `publicId`; throws `UnauthorizedException.invalidToken()` on bad/expired.
  - Secret from `app.jwt.secret` (env `JWT_SECRET`). Fail at startup if shorter than 32 bytes.
- **`user/security/JwtAuthenticationFilter`** (`OncePerRequestFilter`)
  - Reads `Authorization: Bearer …`. No header → continue (public paths still work).
  - Loads the user by `publicId` **from the DB on each request** and rejects inactive users,
    so Agent 3's "disable account" takes effect immediately.
  - Sets `AuthenticatedUser` as principal with authority `ROLE_<role>`.
  - Bad token → let `SecurityErrorHandler` answer 401 (don't write the response yourself).
- **`SecurityConfig`** — register the filter before `UsernamePasswordAuthenticationFilter`.
- **`user/service/AuthService`**
  - `register(RegisterRequest)` → creates `User` (role `CUSTOMER`) **and** its `UserProfile` (fullName) in one transaction. Email lowercased + trimmed. Duplicate → `ConflictException(ErrorCode.EMAIL_ALREADY_EXISTS, …)`.
  - `login(LoginRequest)` → wrong email or password → `UnauthorizedException.invalidCredentials()` (same message for both). Inactive → `ForbiddenException.accountDisabled()`. Updates `lastLoginAt`.
  - `refresh(RefreshTokenRequest)` → **rotation**: old token revoked, new one issued. A revoked token being reused → revoke *all* the user's tokens (`revokeAllByUserId`) and return 401.
  - `logout(RefreshTokenRequest)` → revoke that token. Unknown token → still 204.
  - `changePassword(AuthenticatedUser, ChangePasswordRequest)` → check current password, save new hash, revoke all refresh tokens.
  - Refresh tokens: 32+ random bytes (`SecureRandom`, Base64-url). **Store only the SHA-256 hash** in `refresh_tokens.token`; return the raw value to the client.
- **`user/controller/AuthController`**

### Endpoints
| Method | Path | Auth | Body → Response |
|---|---|---|---|
| POST | `/api/auth/register` | public | `RegisterRequest` → 201 `AuthResponse` |
| POST | `/api/auth/login` | public | `LoginRequest` → 200 `AuthResponse` |
| POST | `/api/auth/refresh` | public | `RefreshTokenRequest` → 200 `AuthResponse` |
| POST | `/api/auth/logout` | public | `RefreshTokenRequest` → 204 |
| PUT | `/api/me/password` | logged in | `ChangePasswordRequest` → 204 |

### Tests (at least)
- `register_duplicateEmail_returns409`, `register_mixedCaseEmail_storedLowercase`
- `login_wrongPassword_returns401`, `login_disabledUser_returns403`
- `refresh_validToken_rotatesAndOldTokenStopsWorking`
- `refresh_reusedRevokedToken_revokesAllUserTokens`
- `protectedEndpoint_noToken_returns401`, `protectedEndpoint_expiredToken_returns401`
- `changePassword_wrongCurrent_returns401` (or 422 — pick one, document it)

---

## Agent 2 — Profile & addresses

**Goal:** a logged-in user can see/edit their profile; a customer manages their addresses.

### Build
- **`user/service/ProfileService`**
  - `getMe(AuthenticatedUser)` → `ProfileResponse` (via `UserProfileMapper`).
  - `updateMe(AuthenticatedUser, UpdateProfileRequest)` → `ProfileResponse`.
  - Missing profile → `NotFoundException.of("Profile")`.
- **`user/service/AddressService`**
  - `list(me, Pageable)` → `PageResponse<AddressResponse>` (default sort: default address first, then newest; max page size 100).
  - `get(me, publicId)`, `create(me, AddressRequest)`, `update(me, publicId, AddressRequest)`, `delete(me, publicId)`, `setDefault(me, publicId)`.
  - **Every lookup uses `findByPublicIdAndUserId`** — someone else's address → 404, never 403.
  - **Default rules:** first address is always default. Setting a default → `clearDefault(userId)` then set, in one transaction. Deleting the default → make the newest remaining address default.
  - **CITY:** pin must be inside Phnom Penh. Use a bounding box constant (approx. lat 11.40–11.75, lng 104.75–105.05) → `BadRequestException` with `details` `{latitude/longitude: …}`.
  - **PROVINCE:** call `deliveryCompanyService.assertActive(deliveryCompanyId)` (Step 0). Don't use `DeliveryCompanyRepository` — it belongs to the delivery module.
  - Max 10 addresses per user → `BusinessRuleException`.
- **`user/controller/ProfileController`**, **`user/controller/AddressController`**

### Endpoints
| Method | Path | Auth | Body → Response |
|---|---|---|---|
| GET | `/api/me` | logged in (any role) | → `ProfileResponse` |
| PUT | `/api/me/profile` | logged in | `UpdateProfileRequest` → `ProfileResponse` |
| GET | `/api/addresses?page=&size=` | `CUSTOMER` | → `PageResponse<AddressResponse>` |
| GET | `/api/addresses/{publicId}` | `CUSTOMER` | → `AddressResponse` |
| POST | `/api/addresses` | `CUSTOMER` | `AddressRequest` → 201 `AddressResponse` |
| PUT | `/api/addresses/{publicId}` | `CUSTOMER` | `AddressRequest` → `AddressResponse` |
| DELETE | `/api/addresses/{publicId}` | `CUSTOMER` | → 204 |
| PUT | `/api/addresses/{publicId}/default` | `CUSTOMER` | → `AddressResponse` |

Address endpoints: `@PreAuthorize("hasRole('CUSTOMER')")`.

### Tests (at least)
- `getAddress_otherUsersAddress_returns404`
- `createAddress_firstAddress_becomesDefault`
- `setDefault_newAddress_unsetsPreviousDefault`
- `deleteAddress_default_promotesNewestRemaining`
- `createAddress_cityPinOutsidePhnomPenh_returns400`
- `createAddress_inactiveDeliveryCompany_returns422`
- `updateProfile_validRequest_updatesAndKeepsPointsBalance`
- `addresses_staffUser_returns403`

---

## Agent 3 — Admin users & delivery companies

**Goal:** the admin manages accounts and the delivery company list; customers can read active companies.

### Build
- **New DTOs** (new files only): `CreateStaffRequest` (email, password, fullName, role, position),
  `UpdateUserRoleRequest` (role), `UpdateUserStatusRequest` (active).
- **`user/service/UserAdminService`**
  - `list(Pageable, role filter optional, search by email optional)` → `PageResponse<UserResponse>`.
  - `get(publicId)` → `UserResponse`.
  - `createStaff(CreateStaffRequest)` → user + profile; role must be `STAFF` / `MANAGER` / `ADMIN` (customers self-register). Email lowercased; duplicate → `EMAIL_ALREADY_EXISTS`.
  - `changeRole(me, publicId, request)`, `changeStatus(me, publicId, request)`.
  - Disabling a user → also `refreshTokenRepository.revokeAllByUserId` (logged out everywhere).
  - **Safety rules → `BusinessRuleException`:** an admin can't disable or demote themselves; the last active ADMIN can't be disabled or demoted.
- **`delivery/service/DeliveryCompanyService`** — keep `assertActive` from Step 0, add:
  `listActive(Pageable)`, `listAll(Pageable)`, `get(id)`, `create`, `update`, `delete`.
  Delete = **soft delete** (`active = false`): addresses reference companies, a hard delete would fail on the FK.
- **Controllers:** `user/controller/AdminUserController`, `delivery/controller/AdminDeliveryCompanyController`, `delivery/controller/DeliveryCompanyController`.

### Endpoints
| Method | Path | Auth | Body → Response |
|---|---|---|---|
| GET | `/api/admin/users?role=&q=&page=&size=` | `ADMIN` | → `PageResponse<UserResponse>` |
| GET | `/api/admin/users/{publicId}` | `ADMIN` | → `UserResponse` |
| POST | `/api/admin/users` | `ADMIN` | `CreateStaffRequest` → 201 `UserResponse` |
| PATCH | `/api/admin/users/{publicId}/role` | `ADMIN` | `UpdateUserRoleRequest` → `UserResponse` |
| PATCH | `/api/admin/users/{publicId}/status` | `ADMIN` | `UpdateUserStatusRequest` → `UserResponse` |
| GET | `/api/admin/delivery-companies` | `ADMIN` | → `PageResponse<DeliveryCompanyResponse>` (all) |
| POST | `/api/admin/delivery-companies` | `ADMIN` | `DeliveryCompanyRequest` → 201 |
| PUT | `/api/admin/delivery-companies/{id}` | `ADMIN` | `DeliveryCompanyRequest` → `DeliveryCompanyResponse` |
| DELETE | `/api/admin/delivery-companies/{id}` | `ADMIN` | → 204 (soft delete) |
| GET | `/api/delivery-companies` | logged in | → `PageResponse<DeliveryCompanyResponse>` (active only) |

Every admin method: `@PreAuthorize("hasRole('ADMIN')")` — `/api/admin/**` alone also lets MANAGER/STAFF in.

### Tests (at least)
- `adminUsers_managerRole_returns403`
- `createStaff_customerRole_returns400or422`
- `changeStatus_disableSelf_returns422`, `changeRole_lastAdmin_returns422`
- `changeStatus_disable_revokesRefreshTokens`
- `listDeliveryCompanies_customer_returnsActiveOnly`
- `deleteDeliveryCompany_softDeletes` (add to the existing `DeliveryCompanyServiceTest`)

---

## Rules for every agent

- Follow `CLAUDE.md`: constructor injection, thin controllers (`@Valid` → one service call → DTO), `@Transactional` on writing service methods, return DTOs only, paginate lists (max 100).
- No `Co-Authored-By` lines in commits.
- Don't edit Flyway migrations or add new ones. Need a schema change? Stop and ask the lead.
- Tests: integration tests extend `IntegrationTest`; names `methodName_condition_expectedResult`.
- Before opening the PR: `cd backend && ./mvnw test` passes (Docker must be running).
- Check the endpoints appear correctly in Swagger (`/swagger-ui.html`).

## Merge order

1. Step 0 → `main`.
2. Agents start from that `main` and work in parallel.
3. Merge in any order. Suggested: **Agent 3 → Agent 2 → Agent 1** (Agent 1 switches on real JWT
   auth last, after the others' endpoints are in). After each merge, the next branch rebases on `main` and re-runs `./mvnw test`.
4. After all three: one end-to-end test on `main` — register → login → create address → admin disables user → old access token gets 401.

## Done when

- [x] Step 0 merged
- [ ] Agent 1 merged
- [ ] Agent 2 merged
- [ ] Agent 3 merged
- [ ] End-to-end test passes; update `CLAUDE.md` "Current phase" to Phase 1 done
