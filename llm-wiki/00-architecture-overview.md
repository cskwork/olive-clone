# Architecture Overview

**Summary:** Modular monolith Spring Boot 3.x service in Java 21, organized
as one Gradle project with sub-packages per domain. The monolith is
deliberate (PRD §5.1, §20.1): we keep transactional integrity simple at the
start, and split the highest-traffic domains (search, product, order) into
independent services later (PRD §21.1).

**Invariants & Constraints:**

- One Gradle root, one Spring Boot application, one Postgres schema.
- Domain modules: `member`, `product`, `search`, `cart`, `order`, `payment`,
  `inventory`, `promotion`, `delivery`, `review`, `admin`, `common`.
- Cross-domain communication goes through Spring `ApplicationEventPublisher`
  + an outbox table — never direct repository calls across domains.
- Storage map (PRD §5.2):
  - **Postgres**: members, products, orders, payments, inventory ledger.
  - **Redis**: cache (cache-aside), session, inventory reservation locks.
  - **OpenSearch**: product search index.
  - **Object Storage (S3 / LocalStack)**: product + review images.
  - **Message Queue (Spring Events → Kafka later)**: async post-order work.
- External systems: PG (Toss/KCP-style), 배송사 (CJ/Hanjin-style), 알림
  (SMS/카카오/푸시), 분석 (mock for now).

**Files of interest:**

- `WORKFLOW.md` — Symphony orchestration + Hard rules every ticket honours.
- `Oliveyoung Like Commerce Backend Design.pdf` §5 — recommended structure
  and logical architecture diagram.
- `Oliveyoung Like Commerce Backend Design.pdf` §18 — locked tech stack.
- `Oliveyoung Like Commerce Backend Design.pdf` §19 — phased roadmap (MVP /
  Operational / Scale).
- `Oliveyoung Like Commerce Backend Design.pdf` §21 — MSA split order when
  the time comes.

**Decision log:**

- 2026-05-10 | seed | Locked: modular monolith, Java 21, Spring Boot 3.x,
  Postgres+Flyway, Redis, OpenSearch, S3 (LocalStack), Spring Events +
  Outbox (Kafka later when traffic warrants).
- 2026-05-10 | OLV-001 | Bootstrap: Spring Boot **3.3.5** + Gradle
  **8.10.2** wrapper(distribution-type=all) + Java 21 toolchain. Group
  `com.olive`, artifact `commerce-backend`, version `0.1.0`. Root
  `@SpringBootApplication`은 `com.olive.commerce`에 위치 — 후속 도메인
  티켓이 자동 컴포넌트 스캔 대상.
- 2026-05-10 | OLV-001 | 부트스트랩 단계의 의도된 비활성: `application.yml`이
  `DataSourceAutoConfiguration` / `HibernateJpaAutoConfiguration` /
  `UserDetailsServiceAutoConfiguration`을 exclude. 앞 둘은 OLV-002에서
  Postgres 도입 시 제거, 마지막 한 줄은 OLV-010(회원 도메인)에서 실제
  `UserDetailsService` 주입 시 제거.
- 2026-05-10 | OLV-001 | actuator endpoint는 `health,info`만 노출하고
  `/actuator/health/**` + `/actuator/info`만 SecurityFilterChain에서
  permitAll. `endpoint.health.probes.enabled=true`로 켜져
  `/actuator/health/liveness`·`/readiness`도 함께 노출 — OLV-130(observability)
  의 K8s probe 매핑이 이 위로 직행.
- 2026-05-10 | OLV-002 | Persistence baseline 활성: `DataSourceAutoConfiguration`
  / `HibernateJpaAutoConfiguration` exclude 두 줄 제거, Flyway autoconfig 켜짐
  (`spring.flyway.locations=classpath:db/migration` 명시). docker-compose
  `postgres:16-alpine` (5432, db/user/pass = `commerce`) + Flyway baseline
  `V1__init_baseline.sql` (placeholder) + Testcontainers `@ServiceConnection`
  공유 베이스(`PostgresIntegrationSupport`)가 후속 도메인 티켓의 진입점.
  세부는 `02-persistence-baseline.md`.
- 2026-05-11 | OLV-131 | Health endpoints 구현: `/actuator/health/liveness`(liveness),
  `/actuator/health/readiness`(PG+Redis+OpenSearch), `/actuator/health/batch`(DLQ).
  `PostgresHealthIndicator`, `RedisHealthIndicator`, `OpenSearchHealthIndicator`,
  `BatchHealthIndicator` 구현. `application.yml`에 `management.endpoint.health.group.readiness`
  설정 추가. README.md에 Health Endpoints 섹션 문서화.

**Last updated:** 2026-05-11 by OLV-131.
