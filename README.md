# GYEOL MARKET · Health & Beauty Commerce

[![CI](https://github.com/cskwork/olive-clone/actions/workflows/ci.yml/badge.svg)](https://github.com/cskwork/olive-clone/actions/workflows/ci.yml)

A health and beauty shop built as a backend portfolio piece: a Spring Boot 3
modular monolith (PostgreSQL, Redis, OpenSearch, S3 via LocalStack, Flyway, JWT)
with a React storefront, **결 GYEOL MARKET**, that drives it end to end: browse,
search, product detail, cart, idempotent checkout with coupons and points, mock
payment, order history.

The storefront also builds as a static demo that runs without the backend, so
reviewers can click through the whole purchase flow from a link.

This is an educational project. It is not affiliated with, endorsed by, or
connected to any retailer or beauty brand. Product names and images are
generated sample data for local development and portfolio presentation only.

| Desktop (1440×900) | Phone (390×844) |
| --- | --- |
| ![Storefront home, desktop](docs/assets/screenshots/storefront-desktop.png) | ![Storefront home, phone](docs/assets/screenshots/storefront-mobile.png) |

## Architecture

```mermaid
flowchart LR
    subgraph Client
        SPA["React storefront<br/>(Vite, TanStack Query)"]
        Demo["Demo build (VITE_DEMO=1)<br/>in-browser mock API"]
    end

    subgraph App["Spring Boot modular monolith"]
        direction TB
        Edge["Security filter chain<br/>JWT RS256 · Bucket4j rate limit · request id"]
        Mods["Domain modules<br/>member · product · cart · order · payment<br/>inventory · promotion · delivery · review · wishlist"]
        Outbox[("outbox_events")]
        Worker["Outbox indexer worker<br/>FOR UPDATE SKIP LOCKED, retry → DLQ"]
        Batch["Batch jobs<br/>ShedLock"]
        Edge --> Mods
        Mods -- "same transaction" --> Outbox
        Outbox --> Worker
    end

    PG[("PostgreSQL 16<br/>source of truth, Flyway")]
    Redis[("Redis<br/>cache-aside, idempotency,<br/>login throttling")]
    OS[("OpenSearch<br/>search, autocomplete")]
    S3[("S3 / LocalStack<br/>images")]

    SPA -- "/api/*" --> Edge
    Mods --> PG
    Mods --> Redis
    Mods --> S3
    Worker --> OS
    Mods -- "search reads" --> OS
    Batch --> PG
```

One deployable, many modules. Each domain package (`member`, `product`, `cart`,
`order`, `payment`, `inventory`, `promotion`, `delivery`, `review`, `search`,
`batch`, `wishlist`) owns its entities, repositories, and rules; cross-module work
goes through services and Spring application events rather than shared tables.
Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Key Design Decisions

- **Postgres is the only source of truth.** Redis and OpenSearch can be rebuilt
  from it at any time (`./gradlew reindexProducts`).
- **Transactional outbox for search indexing.** A product write inserts an
  `outbox_events` row in the same transaction. A worker polls every second with
  `SELECT … FOR UPDATE SKIP LOCKED`, bulk-indexes into OpenSearch, marks rows
  `DONE`, and after 5 failed attempts parks them in a dead-letter state that an
  admin endpoint can replay. OpenSearch being down never fails or loses a write.
- **Cache-aside with explicit invalidation.** Product detail, product lists
  (versioned keys), and the category tree are cached in Redis with short TTLs;
  admin writes evict or bump the version so the next read is fresh.
- **Idempotent money paths.** `POST /api/orders` requires an `Idempotency-Key`
  header (Redis fast path, database uniqueness as the backstop), payment
  confirmation checks the amount against the order and returns
  `PAYMENT_AMOUNT_MISMATCH` (422) on tampering, and PG webhooks are de-duplicated
  with `SETNX`.
- **Auth.** Stateless RS256 JWT access tokens (30 min) plus 14-day refresh
  tokens kept server-side as SHA-256 hashes, rotated on every refresh under a row
  lock (a replayed token is rejected) and revoked on logout. Roles use a hierarchy,
  login attempts are throttled in Redis, and public auth/catalog/search endpoints
  are rate-limited with Bucket4j. The storefront shares a single in-flight refresh
  across concurrent 401s so a rotating refresh token is never spent twice.
- **One error envelope.** Every response is `{ success, data | error, meta }`
  with a stable `error.code` and a `traceId` that matches the `X-Request-Id`
  header and the structured logs.
- **Tests against real infrastructure.** Integration tests run on Testcontainers
  (Postgres, Redis, LocalStack, OpenSearch) instead of mocks or H2.

## Storefront Demo (no backend)

`npm run build:demo` builds the storefront with `VITE_DEMO=1`. In that mode every
`/api` call is answered in the browser by `frontend/src/demo/mockApi.ts`, using a
catalog copied from the Flyway seed (a test fails if the two drift apart). Cart,
orders, points, and coupons persist in `localStorage`; a notice at the top of
every page says so and offers a reset. Real API mode is unchanged.

```bash
cd frontend
npm ci
npm run build:demo        # output: frontend/dist (served at /)
npm run preview:demo      # http://localhost:4173
```

Deploying to Vercel: import the repo, set **Root Directory** to `frontend`. The
committed `frontend/vercel.json` sets the install command (`npm ci`), build
command (`npm run build:demo`), output directory (`dist`), and the SPA rewrite.
No environment variables are required (`frontend/.env.demo` sets `VITE_DEMO=1`;
setting it in the dashboard also works).

Demo login: the form is pre-filled with `demo@example.com` / `demo1234`; any other
email signs in as a new demo member.

## What This Shows

Storefront (React SPA at `/app`, or standalone in demo mode):

- Browse anonymously, then catalog → product detail → cart → checkout → mock
  payment → order complete; actions that need an account send you to login and
  back to where you were
- Wishlist, my page (points, coupons, order summary), order history, and search
  with filters
- Single-flight token refresh so an expired session recovers instead of silently
  logging out

Backend (Spring Boot modular monolith):

- Public catalog API, OpenAPI 3 contract / Swagger UI, and a Thymeleaf smoke
  console at `/products`
- Product, brand, category, option, image, and inventory domain modeling
- Member signup/login with JWT access and refresh tokens
- Cart, order creation, cancellation, payment confirmation, refunds, and mock PG
  webhooks
- Delivery lifecycle with carrier retry handling
- Purchase-verified reviews and review aggregate updates
- OpenSearch product indexing, public search, autocomplete, and popular keywords
- Outbox-based async indexing and failure retry behavior
- Batch jobs, job admin endpoints, sales summary tables, and scheduler locking
- Token-bucket rate limiting on public auth, catalog, and search endpoints
- Actuator health groups, Prometheus metrics, Grafana dashboard provisioning, and
  k6 load-test scripts
- Testcontainers-backed integration tests across Postgres, Redis, LocalStack S3,
  and OpenSearch

## Tech Stack

This repository pairs a backend-heavy commerce system with a real storefront SPA,
not a static mock. The React storefront and the legacy Thymeleaf console both call
the same public product API, while the backend keeps realistic infrastructure
boundaries for data, cache, search, files, observability, and async work.

| Layer | What is used | How it is used in this project |
| --- | --- | --- |
| Language and build | Java 21, Gradle Kotlin DSL, Spring Boot Gradle plugin | Java 21 is the runtime baseline; Gradle wrapper builds, tests, and runs custom boot tasks such as `reindexProducts`. |
| Application framework | Spring Boot 3.3, Spring MVC, Bean Validation | REST controllers, request validation, service wiring, configuration properties, and local command-style tasks. |
| Storefront SPA | React 18, Vite 5, TypeScript, React Router 6, TanStack Query 5 | The customer storefront served at `/app`: browse, cart, anonymous-cart merge on login, checkout, order tracking, wishlist, my page, and search. Vite builds it into `static/app`, and the boot jar packages it for self-contained runs. |
| Server-side UI | Thymeleaf, static CSS/JS | `/products` is a smoke catalog console that loads data from `/api/products` and renders seeded product images. |
| API documentation | springdoc-openapi, Swagger UI | OpenAPI 3 contract the storefront SPA consumes; browsable at `/swagger-ui.html` with JSON at `/v3/api-docs`. |
| Security | Spring Security, OAuth2 Resource Server, Nimbus JOSE JWT | JWT-protected member/admin APIs with local RS256 signing keys for development. |
| Edge protection | Bucket4j | Token-bucket rate limiting on public auth, catalog, and search endpoints. |
| Database | PostgreSQL 16 | Source of truth for products, members, carts, orders, payments, inventory, delivery, reviews, promotions, batch runs, and outbox rows. |
| Migrations | Flyway 10 | Versioned schema and seed migrations, including the 13-product demo catalog and local image URL migration. |
| ORM and repositories | Spring Data JPA, Hibernate | Domain repositories and entity mapping for the modular monolith. |
| Cache and distributed coordination | Redis, Redisson, ShedLock JDBC provider | Catalog cache behavior, inventory/scheduler locking, and safe scheduled job execution. |
| Search | OpenSearch Java client, OpenSearch REST client | Product indexing, search, autocomplete, popular keywords, and a rebuild task from Postgres. |
| Async consistency | Transactional outbox table, index worker | Product changes are recorded in Postgres first, then retried into OpenSearch without losing source data. |
| Object storage | AWS SDK for Java S3 client, LocalStack | S3-compatible local image/upload flows without needing a cloud account. |
| Observability | Actuator, Micrometer, Prometheus, Grafana, logstash-logback-encoder | Health groups, Prometheus metrics, provisioned dashboard, alert rules, and structured logging. |
| Testing | JUnit 5, MockMvc, Spring Security Test, Spring Boot Test, Testcontainers | Controller/service tests and integration tests with real Postgres, Redis, LocalStack, and OpenSearch containers. |
| Load testing | k6 | Product-list and order-create scripts under `infra/k6`. |
| Local runtime | Docker Compose | One-command local dependencies for Postgres, Redis, LocalStack, OpenSearch, Prometheus, and Grafana. |

More detail: [docs/TECH_STACK.md](docs/TECH_STACK.md)

## Quick Start

Prerequisites:

- Docker Desktop or a Docker-compatible runtime
- JDK 21. The Gradle wrapper is included, so no system Gradle install is needed.
- Node.js 20+ and npm. `bootRun` and `bootJar` build the storefront SPA via Vite.
- OpenSSL for generating local JWT signing keys

Generate local development JWT keys. These files are intentionally ignored by
Git.

```bash
mkdir -p src/main/resources/keys
openssl genpkey -algorithm RSA -out src/main/resources/keys/app.key -pkeyopt rsa_keygen_bits:2048
openssl rsa -in src/main/resources/keys/app.key -pubout -out src/main/resources/keys/app.pub
```

Start local infrastructure.

```bash
docker compose up -d postgres redis localstack opensearch
```

Run the app.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Open the demo:

- Storefront SPA: http://localhost:8080/app
- API docs (Swagger UI): http://localhost:8080/swagger-ui.html
- Thymeleaf catalog console: http://localhost:8080/products
- Product API: http://localhost:8080/api/products?size=20
- Search API: http://localhost:8080/api/search/products?keyword=%EC%84%A0%ED%81%AC%EB%A6%BC&page=0&size=5
- Health: http://localhost:8080/actuator/health
- Prometheus metrics: `/actuator/prometheus` is protected by the app security
  filter. Scrape it with credentials or adjust local security for a metrics-only
  demo.

Populate OpenSearch after the app and OpenSearch are running:

```bash
./gradlew reindexProducts --args='--spring.profiles.active=local,reindex --server.port=8082'
```

## Storefront SPA

The React storefront lives in `frontend/` and is served by Spring Boot at `/app`.
`./gradlew bootRun` and `./gradlew bootJar` build it automatically (Vite output
goes to `src/main/resources/static/app`), so the packaged jar is self-contained.

For frontend iteration, run the Vite dev server with hot reload. It proxies
`/api` to the backend on `:8080`:

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173/app
```

Type-check and run the storefront tests:

```bash
npm run typecheck
npm test         # Vitest: demo API contract + seed-parity checks
```

## Local Demo Data

Flyway migrations seed a small health and beauty catalog with 13 products across
skincare, makeup, and hair/body categories. This keeps the UI useful on a fresh
local database without requiring external fixtures. Demo product images are
checked in as local static assets, so the catalog renders without external image
hosting.

More detail: [docs/LOCAL_DEMO.md](docs/LOCAL_DEMO.md)

## Running Tests

```bash
./gradlew test            # 399 backend tests; Docker must be running (Testcontainers)
cd frontend && npm test   # storefront tests (no Docker)
```

GitHub Actions runs both on every push and pull request
([.github/workflows/ci.yml](.github/workflows/ci.yml)), plus both storefront builds.

A few tests capture HTTP evidence files. They write to `build/qa-evidence` by
default; `./gradlew test -Dqa.evidenceRoot=docs` refreshes the committed copies
under `docs/OLV-00x/qa`.

## API Overview

The public demo focuses on catalog and search, but the backend includes member,
cart, order, payment, delivery, review, promotion, inventory, and admin APIs.

Read the API map: [docs/API_OVERVIEW.md](docs/API_OVERVIEW.md)

## Observability and Load Testing

Start the observability stack:

```bash
docker compose up -d prometheus grafana
```

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000, default local credentials `admin` / `admin`
  (override with `GRAFANA_ADMIN_PASSWORD`)
- k6 scripts: [infra/k6/README.md](infra/k6/README.md)

The Grafana dashboard is provisioned from
[infra/grafana/dashboards/commerce-backend.json](infra/grafana/dashboards/commerce-backend.json).

## Documentation Map

- [docs/LOCAL_DEMO.md](docs/LOCAL_DEMO.md): fresh-clone local setup and demo URLs
- [docs/TECH_STACK.md](docs/TECH_STACK.md): stack choices and why they are here
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): module boundaries and data flow
- [docs/API_OVERVIEW.md](docs/API_OVERVIEW.md): endpoint groups and sample calls
- [docs/ASSET_PROVENANCE.md](docs/ASSET_PROVENANCE.md): generated demo image, font, and logo provenance
- [llm-wiki/INDEX.md](llm-wiki/INDEX.md): deeper implementation notes by domain
- [infra/k6/README.md](infra/k6/README.md): load-test scripts
- [CONTRIBUTING.md](CONTRIBUTING.md): contribution and verification workflow
- [SECURITY.md](SECURITY.md): local credentials and reporting policy

## Production Notes

This project is a portfolio-grade backend, not a hosted production deployment.
Before using it for real traffic, replace all local credentials, store JWT keys
in a secret manager, configure a real PG provider, harden CORS/rate limits, and review every `TODO` that marks an intentionally
mocked or simplified integration.

## License

MIT. See [LICENSE](LICENSE).
