# OLV-074 Plan Candidates

## Option A: PaymentService에 refund 통합 (추천)

### 구조
```
payment/
├── Refund.java (엔티티)
├── RefundRepository.java
├── RefundService.java (환불 로직 집중)
└── PaymentService (기존, webhook만 연동)
```

### 장점
- Payment 도메인 캡슐화 유지
- RefundService가 환불 총괄 (requestRefund, approveRefund, rejectRefund)
- 기존 PaymentService와 일관된 패턴

### 단점
- PaymentService가 더 커짐 (하지만 refund는 별도 service로 분리 가능)

### 구현 포인트
1. Refund 엔티티 + Repository
2. RefundService.requestRefund() - 사용자 환불 요청
3. RefundService.approveRefund() - 관리자 승인 (PG 호출 + side effects)
4. RefundService.rejectRefund() - 관리자 거절
5. RefundController - 사용자 엔드포인트
6. RefundAdminController - 관리자 엔드포인트

## Option B: Order 도메인에 환불 배치

### 구조
```
order/
├── OrderService.refundRequest()
└── OrderService.approveRefund()
```

### 장점
- 주문 라이프사이클 관점에서 일관성

### 단점
- Payment 도메인 로직이 Order로 누출
- PG 호출, payment_transactions 기록이 OrderService에서 필요

## Option C: Refund 독립 모듈

### 구조
```
refund/
├── RefundService.java
├── RefundController.java
└── RefundAdminController.java
```

### 장점
- 최고의 응집도

### 단점
- 모듈 수 증가 (현재 프로젝트의 모듈러 모놀리스 패턴과 일치하지 않음)
- Payment와의 연결이 번거로워짐

## 권장: Option A

이유:
1. **도메인 경계 준수**: 환불은 결제의 역연산이므로 payment 패키지에 위치
2. **기존 패턴 일치**: PaymentService-PgClient 패턴과 동일한 구조
3. **확장성**: 향후 partial refund, exchange 등도 RefundService에서 처리 가능
