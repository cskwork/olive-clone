# OLV-052 Domain Brief

## 티켓 개요
포인트 원장 서비스 구축 - 적립/사용/취소/소멸 기능과 회원용 조회 API

## 핵심 발견

### 1. 스키마 구조 (V6__promotion.sql)
- `points`: 회원별 포인트 잔액 캐시 (balance 컬럼)
- `point_histories`: 포인트 원장 (source of truth)
  - change_type: EARN, USE, CANCEL, EXPIRE, ADMIN_ADJUST
  - amount: 항상 양수 (change_type으로 부호 결정)
  - available_at: 포인트 사용 가능 시점 (미래 적립용)
  - expires_at: 포인트 소멸 일자 (NULL = 무기한)
  - order_id: 연관 주문 ID (선택)

### 2. 트리거 기반 balance 동기화
`update_points_balance()` 트리거가 point_histories INSERT/UPDATE/DELETE 시
자동으로 points.balance를 갱신합니다:
- EARN, ADMIN_ADJUST: +amount
- USE, CANCEL, EXPIRE: -amount
- available_at <= now() AND (expires_at IS NULL OR expires_at > now())

### 3. OLV-051 쿠폰 서비스 패턴
- 단일 Service 클래스 + Admin/User 컨트롤러 분리
- Repository에 @Lock(PESSIMISTIC_WRITE)로 동시성 제어
- validate() → BusinessException(ErrorCode, reason)
- DTO는 sealed interface로 계층 구조화

### 4. API 패턴 (01-common-conventions.md)
- ApiResponse<T> 봉투
- ErrorCode enum에 httpStatus() 매핑
- MDC traceId 자동 포함

### 5. AC 요구사항 분석
| AC | 구현 포인트 |
|----|-----------|
| Earn 1000 with available_at=now+30d → spendable=0 | spendableBalance()에서 available_at 필터링 |
| Use 500 vs 700 성공, 800 실패 | use()에서 spendable >= amount 검증 |
| Cancel order (earned 1000 + used 200) → net 0 | cancel()에서 EARN+USE 각각 CANCEL 행 생성 |
| balance == recompute parity | 테스트 teardown에서 검증 |
| Concurrent use 10x20 on 100 balance | SELECT FOR UPDATE 락 |

## 설계 결정사항

1. **PointHistory 엔티티**: 상태 없는 append-only 원장
2. **PointService 단일 클래스**: 쿠폰 패턴 따름
3. **동시성 제어**: use()에서 `SELECT * FROM points WHERE member_id=? FOR UPDATE`
4. **spendableBalance()**: 항상 history에서 재계산 (rollup 컬럼 노출 금지 - 힌트)
5. **pending 목록**: available_at > now()인 EARN 행 목록
