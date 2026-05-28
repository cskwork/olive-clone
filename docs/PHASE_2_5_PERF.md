# Phase 2.5 — Performance / high-traffic config (apply to application.yml)

These settings live in `src/main/resources/application.yml`, which is permission-protected
for the agent, so apply them by hand (or grant edit access). They are config-only — no code
change — and directly serve the "handle high traffic / performant" requirement. The Java-side
items (static asset cache headers) are already committed in `SpaWebConfig`.

Merge each block under the matching top-level key (`spring:` / `server:`); do not duplicate keys.

## 1. HikariCP connection pool (HIGH)
Default `maximum-pool-size` is 10 — saturates around ~50 concurrent VUs. Size it for traffic.

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
      connection-timeout: 10000      # 10s — fail fast instead of piling up
      idle-timeout: 300000           # 5m
      max-lifetime: 1800000          # 30m (below DB/router idle cutoffs)
      validation-timeout: 5000
      pool-name: commerce-hikari
```
For the `local` profile keep it smaller (e.g. `maximum-pool-size: 15`) in `application-local.yml`.

## 2. HTTP response compression (MEDIUM)
```yaml
server:
  compression:
    enabled: true
    mime-types: application/json,application/xml,text/html,text/css,application/javascript,image/svg+xml
    min-response-size: 1024          # only compress payloads > 1KB
```

## 3. Graceful shutdown (MEDIUM)
Drain in-flight requests + outbox/scheduled work on SIGTERM (important for rolling deploys/K8s).
```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

## 4. Tomcat thread / connection tuning (LOW–MEDIUM)
```yaml
server:
  tomcat:
    threads:
      max: 200
      min-spare: 20
    accept-count: 100
    max-connections: 10000
```

## 5. Domain policy overrides (optional, now configurable)
`DomainProperties` (committed) reads these; defaults match prior hardcoded values, so this block
is only needed to change them:
```yaml
olive:
  domain:
    free-shipping-threshold: 30000
    default-shipping-fee: 3000
    reservation-ttl: 15m
    anon-cart-ttl: 30d
```

## Still as code (separate task, not yml)
- **Rate limiting (bucket4j)**: add `implementation("com.bucket4j:bucket4j-core:8.10.1")` to
  `build.gradle.kts`, then a per-IP `OncePerRequestFilter` (catalog/search ~100 req/min,
  `/api/auth/*` ~10 req/min) registered before the security filter chain. Needs a running-app
  check to tune limits.

## Verify after applying
```bash
docker compose up -d postgres redis localstack opensearch
./gradlew bootRun --args='--spring.profiles.active=local'
# load test (separate shell):
k6 run infra/k6/<product-list script>.js     # target product-list p95 < 300ms
```
