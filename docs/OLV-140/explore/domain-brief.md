# 도메인 분석: End-to-End 구매 플로우

## 핵심 도메인 흐름 (PRD §9.1)

```
회원가입/로그인 → 상품조회 → 장바구니 → 주문생성 → 결제승인 → 배송완료 → 리뷰작성
```

## 각 단계의 핵심 제약조건

### 1. 인증 (llm-wiki/10-member-domain.md)
- JWT access/refresh 토큰 방식
- `POST /api/auth/signup` → 201 Created
- `POST /api/auth/login` → 200 OK with `accessToken`

### 2. 상품 (llm-wiki/20-product-domain.md)
- Admin: 브랜드/카테고리/상품 생성
- 상품은 여러 옵션(product_options)을 가짐
- 재고는 `product_option_id` 단위로 관리

### 3. 재고 (llm-wiki/30-inventory-domain.md)
- **Reserve-then-commit 패턴 필수**:
  - 주문 생성: `reserve` (inventory_reservations에 HELD 상태)
  - 결제 승인: `commit` (total_quantity 감소, reservation 삭제)
  - 결제 실패: `release` (reserved_quantity 감소)

### 4. 장바구니 (llm-wiki/40-cart-domain.md)
- 편의 저장소, source of truth 아님
- 주문 시점에 재가격/재검증 필수

### 5. 주문 (llm-wiki/60-order-domain.md)
- 8단계 생성 파이프라인:
  1. 회원 검증
  2. 상품 판매 상태 검증
  3. **재고 검증 + 선점**
  4. 쿠폰 사용 가능 여부 검증
  5. 포인트 사용 가능 여부 검증
  6. 최종 결제 금액 계산
  7. 주문 생성 + order_items 스냅샷
  8. 결제 요청 정보 생성
- 상태: PAYMENT_PENDING → PAID → PREPARING → SHIPPING → DELIVERED

### 6. 결제 (llm-wiki/70-payment-domain.md)
- **멱등성 필수**: `Idempotency-Key` 헤더
- Mock PG: `X-Mock-Pg-Behaviour: approve|fail`
- PaymentApprovedEvent 발행 → 배송 생성, 포인트 적립 예정

### 7. 프로모션 (llm-wiki/50-promotion-domain.md)
- 쿠폰: `member_coupons.status`: ISSUED → USED
- 포인트: ledger 기반 (point_histories가 source of truth)
- 배송 완료 후 포인트 spendable 전환 (DeliveryCompletedEvent)

### 8. 배송 (llm-wiki/80-delivery-domain.md)
- Order → Delivery는 1:N (창고 분리 시)
- DeliveryCompletedEvent → 포인트 spendable 전환 + 리뷰 가능 표시

### 9. 리뷰 (llm-wiki/90-review-domain.md)
- 구매 확정(order_items + DELIVERED)만 작성 가능
- product_review_summaries에 집계

## 이벤트 기반 아키텍처 (llm-wiki/96-eventing.md)

### outbox_events 테이블
- PENDING → IN_PROGRESS → DONE/FAILED
- OutboxIndexerWorker가 1초 폴링

### 핵심 이벤트
- `OrderCreatedEvent`: 주문 생성 후
- `PaymentApprovedEvent`: 결제 승인 후 (배송 생성, 포인트 적립 예정)
- `DeliveryCompletedEvent`: 배송 완료 후 (포인트 spendable 전환, 리뷰 가능)
- `ReviewCreatedEvent`: 리뷰 작성 후 (집계 업데이트)

## 테스트 전략 고려사항

### Testcontainers 설정
- Postgres, Redis, OpenSearch, LocalStack 필요
- `@DynamicPropertySource`로 포트 바인딩
- JVM-싱글톤 컨테이너 사용 (`PostgresIntegrationSupport` 패턴)

### 데이터 정리
- 각 테스트 시작: `TRUNCATE ... RESTART IDENTITY CASCADE`
- `member_grades` seed data 재삽입

### 배송 상태 변경
- `DeliveryStatusSyncJob.syncActiveDeliveries()` 직접 호출
- 또는 Mock CarrierClient가 자동 상태 전이

### Outbox 이벤트 대기
- 최대 5초 폴링으로 status 확인
- `PENDING → DONE` 전이 검증
