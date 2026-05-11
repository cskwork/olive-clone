# OLV-052 Plan Candidates

## Option 1: 단일 PointService + User 컨트롤러 (권장)

### 구조
- `PointHistory` 엔티티 (원장)
- `PointHistoryRepository` (JPA)
- `PointService` (비즈니스 로직)
- `PointController` (`/api/me/points`)

### 장점
- OLV-051 쿠폰 패턴과 일관성
- Admin API 없음 (회원 전용)
- 트리거가 balance를 자동 갱신하므로 서비스가 단순

### 단점
- 동시성 제어를 Service에서 직접 구현해야 함

---

## Option 2: Point + PointHistory 분리 설계

### 구조
- `Point` 엔티티 (잔액 캐시)
- `PointHistory` 엔티티 (원장)
- `PointRepository` (FOR UPDATE 지원)
- `PointHistoryRepository`
- `PointService` (분리된 두 Repository 사용)

### 장점
- 명확한 계층 분리

### 단점
- Point 엔티티는 단순 래퍼에 불과 (balance 컬럼 1개)
- 트리거가 이미 동기화하므로 중복

---

## Option 3: QueryDSL 기반 복잡한 집계 쿼리

### 구조
- spendableBalance()를 QueryDSL로 동적 구현
- 복잡한 필터링 지원

### 장점
- 유연한 쿼리

### 단점
- 현재 요구사항에는 과잉 설계
- 단순 SUM 쿼리로 충분

---

## 추천: Option 1

단일 PointService가 V6 트리거와 잘 어울리고, OLV-051 패턴과 일관성을 유지합니다.
