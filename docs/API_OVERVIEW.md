# API Overview

Responses use a consistent envelope:

```json
{
  "success": true,
  "data": {},
  "meta": {}
}
```

Errors use the same top-level shape with an `error.code`, `error.message`,
`error.path`, and `error.traceId`.

## Public Catalog

```bash
curl 'http://localhost:8080/api/products?sort=LATEST&page=0&size=20'
curl 'http://localhost:8080/api/products/1'
curl 'http://localhost:8080/api/brands'
curl 'http://localhost:8080/api/categories'
```

## Public Search

```bash
curl 'http://localhost:8080/api/search/products?keyword=%EC%84%A0%ED%81%AC%EB%A6%BC&page=0&size=5'
curl 'http://localhost:8080/api/search/autocomplete?prefix=%EC%84%A0'
curl 'http://localhost:8080/api/search/popular'
```

Run `./gradlew reindexProducts --args='--spring.profiles.active=local,reindex --server.port=8082'`
after starting OpenSearch if search results are empty on a fresh local database.

## Auth and Member

```bash
curl -X POST 'http://localhost:8080/api/auth/signup' \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"Password123!","name":"Alice","phone":"01012345678"}'

curl -X POST 'http://localhost:8080/api/auth/login' \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"Password123!"}'
```

Protected endpoints expect `Authorization: Bearer <accessToken>`.

## Cart and Order

```bash
curl 'http://localhost:8080/api/cart' -H 'Authorization: Bearer <token>'
curl -X POST 'http://localhost:8080/api/orders' -H 'Authorization: Bearer <token>'
curl -X POST 'http://localhost:8080/api/orders/{orderId}/cancel' -H 'Authorization: Bearer <token>'
```

Order creation and payment confirmation use idempotency keys to prevent duplicate
business effects.

## Admin APIs

Admin endpoints live under `/api/admin/**` and require admin roles.

Representative groups:

- `/api/admin/brands`
- `/api/admin/categories`
- `/api/admin/products`
- `/api/admin/products/{id}/restock`
- `/api/admin/orders`
- `/api/admin/deliveries`
- `/api/admin/jobs`
- `/api/admin/search/reindex`

## Operations APIs

```bash
curl 'http://localhost:8080/actuator/health'
curl 'http://localhost:8080/actuator/health/liveness'
curl 'http://localhost:8080/actuator/health/readiness'
```

`/actuator/prometheus` is exposed by Actuator but protected by the security
filter. Configure Prometheus scrape credentials or a local-only security override
before treating it as a directly curlable endpoint.

## Current Documentation Gap

The project does not yet ship generated OpenAPI/Swagger docs. This file is the
human-readable public map. Adding `springdoc-openapi` is a good next public-facing
polish task.
