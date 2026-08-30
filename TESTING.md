# Testing Strategy

This document explains the reasoning behind the test suite in this project — what's tested, what isn't, and why. The goal isn't maximum coverage; it's demonstrating that testing decisions were made deliberately, based on where bugs actually live.

## Philosophy: Tier by risk, not by convention

Not every method in a service class carries the same risk. This project tests methods with:
- Business rules (stock validation, pricing math, quantity checks)
- Branching logic (conditional flows, multiple exit paths)
- State mutation with side effects (cart totals, cascading updates across entities)

Testing the second category adds test count without adding protection — it's the same signal a mock verifies whether or not you wrote the test. The judgment call of *which is which* is treated as part of the engineering work, not a shortcut.

## Coverage by service

**`CartServiceImplTest`** — Full coverage of `updateProductQuantityInCart`, `addProductToCart`, `deleteProductFromCart`, and `createOrUpdateCartWithItems`. This class had the most business logic (and the most bugs) in the project.

Two confirmed bugs were found and fixed here:
- `updateProductQuantityInCart` blocked *any* update once stock hit zero — including decreasing an existing cart item — because the `OUT_OF_STOCK` guard didn't distinguish "adding to cart" from "reducing what's already there."
- The insufficient-stock check compared the raw quantity *delta* against available stock, instead of the resulting cart quantity. A user could fail a check they should have passed, or pass one they should have failed, depending on what was already in the cart.

A related inconsistency — `OUT_OF_STOCK` and `INSUFFICIENT_STOCK` sharing an error code in one code path — was also identified and corrected. They're distinct conditions (zero stock vs. requested-more-than-available) and the frontend needs to be able to tell them apart.

**`OrderServiceImplTest`** — Covers `placeOrder`, `updateOrder`, and `getAllSellerOrders`, including the stock-decrement and cart-clearing side effects that happen after an order is placed. `getAllOrders` is intentionally excluded: it's a paginated pass-through with no conditional logic to protect.

**`AuthServiceImplTest`** — Covers registration conflict checks (duplicate username/email), role-assignment branching (admin/seller/user), and password encoding.

**`ProductServiceImplTest`** — Full Tier 1 coverage: `addProduct`, `updateProduct`, `deleteProduct`, `updateProductImage`, `searchByCategory`, `searchProductByKeyword`, and `constructImageUrl`. The focus here is on multi-entity side effects and business rules rather than simple field mapping:

- `addProduct` enforces uniqueness by product name *scoped to active products only* — a soft-deleted product isn't allowed to permanently block a name from being reused, which is asserted directly rather than assumed.
- `updateProduct` and `deleteProduct` both cascade into every cart currently holding the product (price re-sync on update, removal on delete), and both paths are tested with zero, one, and multiple affected carts to confirm the cascade doesn't fire when it shouldn't.
- `constructImageUrl`, a private helper, is exercised directly via `ReflectionTestUtils` rather than only indirectly through a public method — it has its own edge case (trailing slash handling) worth asserting on its own.

No bugs surfaced in this class during testing; it's included here as evidence the tiering method holds up even when a class turns out clean, not just when it catches something.

## What's deliberately out of scope for unit tests

- **Specification-based filtering** (`getAllProducts` keyword/category search) — this logic is expressed as JPQL predicates built at runtime. Mockito can verify that a method was called; it can't verify that the resulting query returns the right rows. That requires a real query engine, so this is planned as a `@DataJpaTest` against an in-memory database rather than a Mockito unit test. Testing it with mocks here would bring no actual protection.

## Note on tooling

`ddl-auto=update` is used for local development only. Schema changes here are hand-verified against Hibernate DDL output as they're made (see the `Product.active` column default fix); a production deployment would use Flyway or Liquibase migrations instead, not auto-DDL.

---
