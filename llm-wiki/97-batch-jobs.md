# Batch Jobs (Scheduled Work)

**Summary:** Periodic cleanup, expiry, sync, and aggregation jobs (PRD §17).
Implemented with `@Scheduled` first; migrate to Spring Batch / Quartz when
job volume or operational visibility demands it.

**Invariants & Constraints:**

- Required jobs (PRD §17.1):
  - 결제 대기 주문 만료 처리 — every 5 min.
  - 재고 선점 만료 해제 — every 5 min.
  - 쿠폰 만료 처리 — every day 00:00.
  - 포인트 만료 처리 — every day 00:00.
  - 배송 상태 동기화 — every 10 min.
  - 일 매출 집계 — every day 02:00.
  - 상품 랭킹 집계 — every 1 hour.
  - 리뷰 평점 집계 — every 1 hour.
  - 검색 인덱스 재색인 — manual / weekly.
- Schedules from PRD §17.2:

  ```
  매 5분   : 결제 대기 주문 만료 처리
  매 10분  : 배송 상태 동기화
  매 1시간 : 인기 상품 랭킹 집계
  매일 00시 : 쿠폰/포인트 만료 처리
  매일 02시 : 매출 통계 집계
  ```

- Use `ShedLock` (with Redis/JDBC lock provider) to prevent duplicate
  execution on multi-instance deploys.
- Job runs write a row to a `job_runs` table with `(job_name, started_at,
  finished_at, status, processed_count, error)` for observability.

**Files of interest:**

- PRD §17.

**Decision log:**

- 2026-05-10 | seed | ShedLock with JDBC lock provider (Postgres) —
  zero new infra dependency.

**Last updated:** 2026-05-10 by seed.
