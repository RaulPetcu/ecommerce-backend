# E-Commerce Platform — Backend

A full-stack e-commerce application with a Spring Boot REST backend and React frontend ([separate repo](#)). The backend handles product catalog management, cart operations, order processing, and real Stripe payment integration, secured by cookie-based JWT authentication with role-based access control across three roles: buyer, seller, and admin.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        React Frontend                           │
│                    (separate repository)                        │
└───────────────────────────┬─────────────────────────────────────┘
                            │ HTTP / JSON
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                      │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              Spring Security Filter Chain                │   │
│  │         JWT (cookie-based) · RBAC (3 roles)             │   │
│  └──────────────────────────────────────────────────────────┘   │
│                            │                                    │
│  ┌─────────────────────────▼────────────────────────────────┐   │
│  │                    REST Controllers                      │   │
│  │    Auth · Product · Cart · Order · Category · Address    │   │
│  └─────────────────────────┬────────────────────────────────┘   │
│                            │                                    │
│  ┌─────────────────────────▼────────────────────────────────┐   │
│  │                    Service Layer                         │   │
│  │         Business logic · Transaction boundaries          │   │
│  └──────────┬──────────────────────────────┬───────────────┘   │
│             │                              │                    │
│  ┌──────────▼──────────┐      ┌────────────▼────────────────┐   │
│  │  JPA / Hibernate    │      │       Stripe Java SDK       │   │
│  │  Repositories +     │      │   PaymentIntent · Customer  │   │
│  │  Specifications     │      └─────────────────────────────┘   │
│  └──────────┬──────────┘                                        │
└─────────────┼───────────────────────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────────────────────┐
│                         PostgreSQL                              │
└─────────────────────────────────────────────────────────────────┘
```

---

## Features

### Authentication & Authorization
Cookie-based JWT issued on login and cleared on logout via `ResponseCookie`. On every request, `AuthTokenFilter` — a `OncePerRequestFilter` — extracts the token from the cookie, validates it, and populates the `SecurityContext` with the authenticated principal. The application is fully stateless: `SessionCreationPolicy.STATELESS` ensures no server-side session is created or consulted.

Role enforcement is handled at the URL pattern level in `SecurityFilterChain`: `/api/admin/**` requires `ROLE_ADMIN`, `/api/seller/**` accepts `ROLE_ADMIN` or `ROLE_SELLER`, `/api/public/**` is open, and all remaining endpoints require authentication. Unauthenticated requests receive a structured JSON 401 response via `AuthEntryPointJwt` rather than a redirect, which is appropriate for a stateless REST API consumed by a separate frontend. Passwords are hashed with BCrypt via `DaoAuthenticationProvider`.

### Product Catalog
Full CRUD for sellers, with soft deletion via an `active` flag so deactivated products are excluded from public listings without losing historical order data. Dynamic filtering (keyword + category) is implemented using JPA `Specification` composition. Image URLs are constructed server-side so the client never has to know the storage base URL.

### Shopping Cart
Per-user cart supports adding, quantity adjustment (delta-based), and removal. Two stock validation bugs were identified and fixed during test writing — see [Cart Integrity](#cart-integrity--two-regression-locked-bugs) and [`TESTING.md`](./TESTING.md) for the full writeup.

### Order Processing
Checkout snapshots cart prices into `OrderItem` records at the point of purchase, decoupling order history from subsequent price changes. Order status transitions are managed by admins.

### Payment (Stripe)
Real Stripe `PaymentIntent` flow: the backend looks up or creates a Stripe `Customer` by email, creates a `PaymentIntent` with the cart total, and returns the client secret to the frontend for Stripe.js confirmation. Avoids duplicate Stripe Customer records by searching before creating — repeat calls for an existing email reuse the existing Stripe Customer.

### Seller Dashboard
Sellers can list and manage their own products and view orders containing their items. Product ownership is enforced at the service layer: `getAllProductsForSeller` filters by the authenticated user, and `getAllSellerOrders` cross-references `OrderItem → Product → User`.

### Admin Oversight
Admins have access to the full product catalog (including inactive products), the complete order list with status update capability, and the seller directory.

---

## Notable Engineering Decisions

### Exception Handling Architecture
Rather than throwing `RuntimeException` or a single catch-all exception class, the project uses a layered exception system:

- `ResourceNotFoundException` — 404 responses for missing entities
- `ResourceAlreadyExistsException` — conflict responses on duplicate creation
- `APIException` — all other domain errors, with an attached `ErrorCode` enum

The `@RestControllerAdvice` handler maps each exception type to an appropriate HTTP status. The `ErrorCode` enum (`OUT_OF_STOCK`, `INSUFFICIENT_STOCK`, `NEGATIVE_QUANTITY`, `CART_EMPTY`, `GENERIC`) exists for a specific reason: codes are only added when the frontend would need to **behave differently**, not simply to catalogue every error message. `OUT_OF_STOCK` and `INSUFFICIENT_STOCK` are distinct codes because they warrant different UI responses (product unavailable vs. quantity too high); a generic message difference alone wouldn't justify a separate code.

### Soft Delete with Active-Scoped Queries
Products are never hard-deleted. The `active` flag defaults to `true`; `deleteProduct` sets it to `false`. All public-facing queries filter on `active = true` — either via named repository methods (`findByCategoryAndActiveTrueOrderByPriceAsc`) or via a base `Specification` predicate that is `and`-composed with any additional filters. The duplicate-name check on `addProduct` is also active-scoped: a deactivated product with the same name does not block a new listing.

This design preserves referential integrity for existing `OrderItem` records while keeping deleted products invisible to buyers and irrelevant to business logic.

### Cart Integrity — Two Regression-Locked Bugs
Two stock validation bugs were found and fixed during test writing. Both were subtle in that the incorrect behavior only surfaced in edge cases:

**Bug 1 — Unconditional stock guard blocked valid decreases.**
The `OUT_OF_STOCK` and `INSUFFICIENT_STOCK` guards in `updateProductQuantityInCart` ran regardless of whether the delta was positive or negative. A user who already had an item in their cart could not decrease their quantity if stock had since dropped to zero — the guard fired before the sign of the delta was considered. Fix: guards now only run for positive deltas.

**Bug 2 — Insufficient-stock check compared stock against the delta, not the resulting cart quantity.**
The guard compared `product.quantity < delta` rather than `product.quantity < (cartItem.quantity + delta)`. A small increment could pass the check even when the resulting cart total far exceeded available stock. Fix: the comparison now uses the resulting cart quantity.

Both bugs are documented with root-cause Javadoc in the regression tests and covered in [`TESTING.md`](./TESTING.md).

### JPA-Derived DELETE Queries and In-Memory Sync
`CartItemRepository` uses `@Modifying` JPQL `DELETE` queries for bulk and targeted removal. These bypass the JPA persistence context — the in-memory `cartItems` collection on `Cart` is not updated automatically, regardless of `cascade` or `orphanRemoval` configuration. Any code path that builds a response DTO after a derived DELETE explicitly syncs the collection via `removeIf` before mapping.

### Stripe Customer Idempotency
`StripeServiceImpl` searches for an existing Stripe `Customer` by email before creating one. This means repeat PaymentIntent creation for the same user does not produce duplicate Stripe Customer records, which matters for Stripe's customer portal and payment method management features.

---

## Testing Approach

Tests are tiered by business complexity, not by coverage target. Pure pass-throughs and thin delegation wrappers are explicitly excluded with documented rationale. Service-layer logic is tested with JUnit 5, Mockito, and AssertJ using a real `ModelMapper` instance — not mocked — to catch mapping regressions.

The two regression tests for the cart bugs are annotated with Javadoc describing the precise root cause of each defect and the condition that triggered it, making the intent of each test self-documenting.

For full coverage rationale, tiering decisions, and the bug writeup, see **[TESTING.md](./TESTING.md)**.

---

## API Overview

All endpoints return JSON and the frontend does not handle the token directly.

| Domain      | Method | Path                                      | Access        |
|-------------|--------|-------------------------------------------|---------------|
| **Auth**    | POST   | `/api/auth/signin`                        | Public        |
|             | POST   | `/api/auth/signup`                        | Public        |
|             | POST   | `/api/auth/signout`                       | Authenticated |
|             | GET    | `/api/auth/user`                          | Authenticated |
| **Products**| GET    | `/api/public/products`                    | Public        |
|             | GET    | `/api/public/products?keyword=&category=` | Public        |
|             | POST   | `/api/seller/products/{categoryId}`       | Seller        |
|             | PUT    | `/api/seller/products/{productId}`        | Seller        |
|             | DELETE | `/api/seller/products/{productId}`        | Seller        |
|             | GET    | `/api/admin/products`                     | Admin         |
| **Cart**    | POST   | `/api/carts/products/{productId}/quantity/{qty}` | User   |
|             | GET    | `/api/carts`                              | User          |
|             | PUT    | `/api/cart/products/{productId}/quantity/{qty}`  | User   |
|             | DELETE | `/api/carts/{cartId}/product/{productId}` | User          |
| **Orders**  | POST   | `/api/order/users/payments/{paymentMethod}` | User        |
|             | GET    | `/api/admin/orders`                       | Admin         |
|             | PUT    | `/api/admin/orders/{orderId}/status/{status}` | Admin    |
| **Payments**| POST   | `/api/stripe/payment-intent`              | User          |
| **Categories** | GET | `/api/public/categories`                 | Public        |
|             | POST   | `/api/admin/category`                     | Admin         |
| **Addresses**| POST  | `/api/addresses`                          | User          |
|             | GET    | `/api/addresses`                          | Admin         |
|             | GET    | `/api/users/addresses`                    | User          |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3 |
| Persistence | Spring Data JPA, Hibernate, PostgreSQL |
| Security | Spring Security, JWT (cookie-based) |
| Payments | Stripe Java SDK (PaymentIntent) |
| Mapping | ModelMapper |
| Testing | JUnit 5, Mockito, AssertJ |
| Build | Maven |

---

## Related

- **Frontend** — React application ([repo link](#))
- **Testing documentation** — [`TESTING.md`](./TESTING.md)
