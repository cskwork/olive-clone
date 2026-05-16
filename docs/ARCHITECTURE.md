# Architecture

The backend is a modular monolith. Each domain owns its persistence model and
business rules, while the application remains deployable as one Spring Boot
service.

## Module Map

| Module | Responsibility |
| --- | --- |
| `member` | signup, login, member profile, addresses, grades, refresh tokens |
| `product` | brands, categories, products, options, images, public listing |
| `search` | OpenSearch documents, reindex command, outbox indexing worker |
| `cart` | member and anonymous cart operations |
| `order` | order creation, idempotency, status changes, cancellation |
| `payment` | mock PG integration, payment confirmation, webhook, refunds |
| `inventory` | option-level stock, reservation, commit, release, lock fallback |
| `promotion` | coupons, member coupons, points, point history |
| `delivery` | delivery records, status history, carrier retry queue |
| `review` | purchase-verified reviews, review reports, review aggregates |
| `batch` | scheduled jobs, daily sales summaries, job run audit |
| `common` | config, security, errors, metrics, events, utility classes |
| `ui` | Thymeleaf smoke UI for the public product API |

## Request Flow

1. A controller receives an HTTP request and returns a consistent API envelope.
2. A service validates the command, applies business rules, and writes through a
   repository.
3. Database constraints and Flyway-managed schema protect invariants such as
   order uniqueness, inventory consistency, and status values.
4. Domain changes that require async side effects write an outbox row in the same
   transaction.
5. Workers drain outbox rows, retry failures, and mark rows complete only after
   the side effect succeeds.

## Search Flow

Product writes remain Postgres-first. The search module turns product IDs into
OpenSearch documents by hydrating product, brand, price, and category data from
the database. Public search returns matching product IDs from OpenSearch and then
hydrates the response through the public product DTO shape so search and catalog
responses stay aligned.

## Caching Flow

Public catalog responses use Redis cache-aside behavior. Product admin updates
bump the relevant cache version so the next public request sees fresh data.
Readiness checks include Redis because stale or unavailable cache infrastructure
changes user-facing behavior.

## Reliability Boundaries

- Postgres is the source of truth.
- Redis and OpenSearch are rebuildable infrastructure.
- LocalStack is a local stand-in for S3-compatible object storage.
- Mock PG and mock carrier clients are intentionally local/demo integrations.
- Production deployment should replace mock integrations and local credentials.

