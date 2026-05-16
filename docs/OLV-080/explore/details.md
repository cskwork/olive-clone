# OLV-080 Explore Details

## 인바리언트 상세 (llm-wiki/80-delivery-domain.md)

**Order → Delivery 관계**
- Order는 1:N Delivery 관계 (PRD §6.9)
- MVP는 하나의 Order에 하나의 Delivery (단일 창고)
- 향후 멀티 창고 분할 시 delivery_address_id는 주문의 배송지를 그대로 사용

**Delivery 상태 enum (PRD §6.9)**
```
READY      : 배송 준비
INVOICE    : 운송장 등록
SHIPPING   : 배송중
DELIVERED  : 배송 완료
RETURNING  : 반품중
RETURNED   : 반품 완료
```

**Carrier API 실패 처리 (PRD §15.2)**
- 실패시 delivery_retry_queue에 기록
- delivery.status는 마지막 성공 상태 유지
- 관리자가 백오피스에서 수동 재처리

**감사 가능성**
- 모든 상태 변경은 delivery_status_histories에 기록

## 기존 코드 패턴 분석

**Event 기반 아키텍처**
- PaymentApprovedEvent: orderId, orderNo, paymentId, paymentKey, approvedAmount
- AFTER_COMMIT 페이즈에 발행 (PaymentApprovedEvent.phase())
- @TransactionalEventListener(phase = AFTER_COMMIT)로 수신

**Entity 패턴 (Order)**
- protected Order() {} — JPA 기본 생성자
- static factory 메서드 (create())
- 상태 전이 메서드 (toPaid(), toCanceled() 등)
- @CreationTimestamp, @UpdateTimestamp

**Status History 패턴 (OrderStatusHistory)**
- static factory: initial(), transition()
- ChangedByKind enum (USER, ADMIN, SYSTEM)
- created_at은 @CreationTimestamp

**Client 패턴 (PgClient)**
- 인터페이스 분리
- Mock 구현은 volatile 필드로 동작 제어
- DTO 패키지 분리

## 위험 분석

**R1: delivery_address_id FK 참조**
- orders 테이블의 delivery_address_id는 member_addresses 참조
- delivery.delivery_address_id는 orders.delivery_address_id를 그대로 복사
- FK 제약 조건 필요 없음 (배송지 삭제 시에도 배송 이력은 보존)

**R2: invoice_no NULLABLE 여부**
- READY 상태에서는 invoice_no가 NULL
- INVOICE 상태로 전이 시 운송장 번호 부여
- UNIQUE 제약 조건 필요 (운송장 번호는 전역 유니크)

**R3: Scheduled 실행 중복 방지**
- 멀티 인스턴스 배포 시 동일 배송이 중복 처리될 위험
- ShedLock 도입 필요 (의존성 추가)
- 또는 DB 락-based 구현 (SELECT FOR UPDATE SKIP LOCKED)

**R4: Mock carrier timing**
- 테스트에서 대기 시간을 제어할 수 있어야 함
- @ConditionalOnProperty로 Mock/Real 분리
- 테스트에서는 즉시 전이되는 모드 지원

## PRD 섹션 참조

| 섹션 | 내용 |
|------|------|
| §6.9 | 배송 상태 값 정의, 1:N 관계 |
| §15.2 | 택배사 API 장애 처리: 재시도 큐 |
| §8.4 | 결제 완료 후 배송 준비 트리거 |

## 구현 계획

### V10__delivery.sql
1. deliveries 테이블
2. delivery_status_histories 테이블
3. delivery_retry_queue 테이블
4. 인덱스 및 FK 제약

### Entity & Repository
1. Delivery, DeliveryStatusHistory, DeliveryRetryQueue 엔티티
2. DeliveryStatus enum
3. Repository 인터페이스

### Service
1. DeliveryService (prepareForOrder, issueInvoice, fetchStatus)
2. DeliveryEventPublisher (ApplicationEvent wrapper)
3. CarrierClient 인터페이스
4. MockCarrierClient 구현

### Event
1. PaymentApprovedEvent 리스너 → prepareForOrder 호출
2. DeliveryCompletedEvent 발행

### Scheduled
1. MockDeliveryStatusWalker (1분 간격 상태 전이)
2. DeliveryRetryQueueWorker (실패 요청 재시도)

### Controller
1. DeliveryController (GET /api/me/orders/{orderNo}/deliveries)
2. DeliveryAdminController (POST /api/admin/deliveries/{id}/issue-invoice, GET /api/admin/deliveries)
