# OLV-100 — OpenSearch 인덱스 + outbox 동기 파이프라인 (사용자 가이드)

## 개요

상품 검색을 위한 OpenSearch 인덱스를 띄우고, 상품 데이터 변경을 **outbox 큐**를
통해 1초 이내에 검색 인덱스에 반영하는 비동기 파이프라인을 구축했습니다.

- 검색 인덱스 이름: `products`
- 색인 문서 모양: wiki §95-search-domain의 9개 필드 (`productId`, `productName`,
  `brandName`, `categoryNames`, `tags`, `salePrice`, `rating`, `salesCount`,
  `reviewCount`, `status`).
- 분석기: `standard` (한국어 `nori`는 후속 ticket).

## 어드민 작업 흐름

### 1. 전체 재색인 — `./gradlew reindexProducts`

```bash
./gradlew reindexProducts
```

- `local` + `reindex` 프로필로 부팅 → 모든 `products` 행을 100건씩 묶어 OpenSearch에 bulk index 후 종료.
- 인덱스가 없으면 자동 생성.
- 환경 변수로 프로필 오버라이드: `SPRING_PROFILES_ACTIVE=stg,reindex ./gradlew reindexProducts`.

### 2. 단일 상품 수동 재색인

```http
POST /api/admin/search/reindex/{productId}
```

- 권한: `PRODUCT_ADMIN`.
- 응답: `202 Accepted`, body는 outbox event id와 status.
- 실제 인덱싱은 ≤ 1 초 후 background 워커가 처리.

### 3. 일반 상품 변경 시 자동 동기

- `POST /api/admin/products`, `PATCH /api/admin/products/{id}`, 옵션 추가/수정 — 4가지 admin 경로 모두 outbox에 `PRODUCT_INDEX_SYNC` 행을 추가.
- 같은 트랜잭션에서 insert되므로 상품 변경이 롤백되면 동기 요청도 사라짐 (at-least-once 보장).

## 장애 시나리오

| 상황 | 동작 |
|------|------|
| OpenSearch 다운 | 워커가 attempt_count를 늘리며 계속 재시도. 앱은 정상. |
| 5회 연속 실패 | row의 `dlq=true`로 전환. 어드민이 수동 DLQ 처리(후속). |
| 부팅 시 OpenSearch 미가용 | 인덱스 생성은 스킵되지만 부팅은 성공. 인프라 복구 후 어드민이 재인덱스 트리거 필요. |

## 운영 점검 쿼리

```sql
-- PENDING 백로그 확인
SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING' AND dlq = FALSE;

-- DLQ에 빠진 row 목록
SELECT id, aggregate_id, attempt_count, last_error
FROM outbox_events WHERE dlq = TRUE;
```

## 후속 작업 (별도 ticket)

- `nori` 한국어 분석기 — 도커 이미지 빌드 시 plugin 추가 + analyzer 매핑 변경.
- DLQ 해제 어드민 UI / API.
- tags 도메인 신설 후 `tags` 필드 활성화.
- review 도메인 신설 후 `rating`/`reviewCount` 활성화.
