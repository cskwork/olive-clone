# OLV-051 Plan Candidates

## 옵션 1: 단일 CouponService + Admin/User 컨트롤러 분리

**구조**:
```
promotion/
├── Coupon.java                    @Entity
├── MemberCoupon.java              @Entity
├── CouponRepository.java          JpaRepository + @Lock
├── MemberCouponRepository.java    JpaRepository
├── CouponDtos.java                sealed interface (admin + user DTOs)
├── CouponService.java             validate(), markUsed(), restore(), tryReserve(), bulkIssue()
├── CouponAdminController.java     /api/admin/coupons/*
├── MemberCouponController.java    /api/me/coupons/*
└── CouponInvalidReason.java       enum (EXPIRED, NOT_OWNED, ...)
```

**장점**:
- 쿠폰 도메인 로직이 한 서비스에 집중
- OLV-061에서 호출할 `validate()`, `tryReserve()`가 명확히 노출
- Admin/User API가 같은 도메인 로직을 재사용

**단점**:
- `CouponService`가 커질 수 있음 (CRUD + 발급 + 검증 + 사용)
- Bulk issue의 동시성 제어 로직이 복잡해질 수 있음

---

## 옵션 2: CouponAdminService + CouponUserSerivce 분리 + 공통 CouponValidator

**구조**:
```
promotion/
├── Coupon.java                    @Entity
├── MemberCoupon.java              @Entity
├── CouponRepository.java
├── MemberCouponRepository.java
├── CouponDtos.java
├── CouponValidator.java           validate()만 담당 (순수 함수)
├── CouponAdminService.java        createCoupon(), updateStatus(), bulkIssue()
├── CouponUserService.java         listMyCoupons(), tryReserve()
├── CouponAdminController.java
└── MemberCouponController.java
```

**장점**:
- 관심사 분리: Admin CRUD vs User 검증/사용
- `CouponValidator`는 독립적으로 테스트 가능

**단점**:
- `markUsed()`/`restore()`는 어디에? (AdminService인가 UserService인가?)
- 계층이 늘어나서 호출 체인이 길어짐

---

## 옵션 3: 세분화된 서비스 + 도메인 이벤트 (추후 확장 고려)

**구조**:
```
promotion/
├── domain/
│   ├── Coupon.java
│   ├── MemberCoupon.java
│   └── CouponIssuedEvent.java    @ApplicationEvent
├── repository/
│   ├── CouponRepository.java
│   └── MemberCouponRepository.java
├── service/
│   ├── CouponAdminService.java
│   ├── CouponValidationService.java
│   └── CouponIssuanceService.java   bulkIssue 전담
├── web/
│   ├── CouponAdminController.java
│   └── MemberCouponController.java
└── dto/
    └── CouponDtos.java
```

**장점**:
- 향후 이벤트 기반 확장에 유리 (예: 쿠폰 발급 알림)
- 계층 분리로 테스트가 용이

**단점**:
- 현재 티켓 범위에는 과잉 설계
- 패키지 depth가 깊어짐 (현재 프로젝트는 flat structure 선호)

---

## 추정 작업량

| 옵션 | Entity | Repository | Service | Controller | Test | 합계 |
|-----|--------|-----------|---------|-----------|------|-----|
| 1   | 2      | 2         | 1       | 2         | 5-6  | ~12 |
| 2   | 2      | 2         | 3       | 2         | 6-7  | ~14 |
| 3   | 2      | 2         | 3       | 2         | 6-7  | ~14 |

**추천**: 옵션 1 (단일 CouponService) — 티켓 범위에 맞고, OLV-061 의존관계가 가장 명확함
