# OLV-090 Explore: Review Domain 详细分析

## 1. 기존 도메인 패턴 분석

### 1.1 Entity 패턴 (Order, Delivery 기준)
- **생성자**: `protected` 기본 생성자 + `private` 생성자 (필수 파라미터)
- **팩토리 메서드**: `public static X create(...)` 정적 팩토리 메서드
- **상태 관리**: 내부 `validateTransition()` 메서드로 상태 천이 유효성 검증
- **Timestamp**: `@CreationTimestamp`, `@UpdateTimestamp` (Hibernate)
- **연관관계**: `@ManyToOne`, `@OneToMany` 양방향, `@JoinColumn` 명시

### 1.2 Repository 패턴
- `JpaRepository` 상속
- 커스텀 조회 메서드: `findByXxxYyy(Zzz xxx)`
- 페이지네이션: `Page<Xxx> findBy...(..., Pageable pageable)`

### 1.3 Service 패턴
- 생성자 주입 (`final` 필드 + 생성자)
- `@Transactional` 메서드 레벨 트랜잭션
- 비즈니스 예외: `BusinessException(ErrorCode, String detail)`
- 소유권 검증: `entity.getMemberId().equals(principal.memberId())`

### 1.4 Controller 패턴
- `@RestController` + `@RequestMapping`
- `@AuthenticationPrincipal AuthenticatedUser`로 회원 ID 주입
- 응답: `ResponseEntity<ApiResponse<T>>`
- 페이지네이션: `@RequestParam(defaultValue="0") int page`

### 1.5 Event 패턴
- **Event**: `ApplicationEvent` 상속, `record` 스타일 (불변)
- **Publisher**: `ApplicationEventPublisher.publishEvent()`
- **Listener**: `@TransactionalEventListener(phase=AFTER_COMMIT)` + `@Async`

## 2. 리뷰 도메인 요구사항 분석

### 2.1 Schema (V10__review.sql)
```sql
-- reviews
CREATE TABLE reviews (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL REFERENCES members(id),
    product_id BIGINT NOT NULL REFERENCES products(id),
    order_item_id BIGINT NOT NULL UNIQUE REFERENCES order_items(id),
    rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title VARCHAR(255),
    body TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE' CHECK (status IN ('VISIBLE', 'HIDDEN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- review_images
CREATE TABLE review_images (
    id BIGSERIAL PRIMARY KEY,
    review_id BIGINT NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    url VARCHAR(500) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- review_reports
CREATE TABLE review_reports (
    id BIGSERIAL PRIMARY KEY,
    review_id BIGINT NOT NULL REFERENCES reviews(id),
    reporter_member_id BIGINT NOT NULL REFERENCES members(id),
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RESOLVED', 'DISMISSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- product_review_summaries
CREATE TABLE product_review_summaries (
    product_id BIGINT PRIMARY KEY REFERENCES products(id),
    avg_rating DECIMAL(3,2) NOT NULL DEFAULT 0.00,
    review_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 2.2 자격 검증 (Eligibility Check)
리뷰 작성 자격: `order_items` 테이블에서 다음 조건 충족
```sql
SELECT 1 FROM order_items oi
JOIN orders o ON oi.order_id = o.id
WHERE oi.id = :orderItemId
  AND o.member_id = :memberId
  AND o.status = 'DELIVERED'
  AND NOT EXISTS (
      SELECT 1 FROM reviews r WHERE r.order_item_id = oi.id
  )
```

### 2.3 API 설계

**User endpoints**:
- `POST /api/me/reviews` - 리뷰 작성 (422: 배송 완료 전, 409: 이미 작성)
- `GET /api/products/{productId}/reviews?sort=latest|helpful&page=&size=` - 리뷰 목록 (VISIBLE만)
- `POST /api/me/reviews/{reviewId}/report` - 리뷰 신고

**Admin endpoints** (`CS_MANAGER` 이상):
- `GET /api/admin/review-reports?status=` - 신고 목록
- `POST /api/admin/reviews/{reviewId}/hide` - 리뷰 숨김

### 2.4 Aggregate 유지보수
`ReviewCreatedEvent` 발행 시:
```sql
INSERT INTO product_review_summaries (product_id, avg_rating, review_count, updated_at)
VALUES (:productId, :rating, 1, now())
ON CONFLICT (product_id) DO UPDATE SET
    avg_rating = (
        (avg_rating * review_count + :rating) / (review_count + 1)
    ),
    review_count = review_count + 1,
    updated_at = now();
```

## 3. 새로운 ErrorCode 추가 필요
```java
REVIEW_ELIGIBLE_ORDER_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY), // 배송 완료 전 리뷰 작성
REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT),                     // 이미 리뷰 작성됨
REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND),                         // 리뷰 없음
REVIEW_NOT_OWNED(HttpStatus.FORBIDDEN),                         // 타인 리뷰 수정/삭제 시도
REVIEW_REPORT_NOT_FOUND(HttpStatus.NOT_FOUND)                   // 신고 없음
```

## 4. 테스트 전략
1. **SchemaIntegrationTest**: V10 마이그레이션 적용 검증
2. **ReviewServiceTest**: 자격 검증, 중복 작성 방지
3. **ReviewApiIT**: API 엔드포인트 통합 테스트
4. **ReviewAggregateTest**: avg_rating 계산 정확성

## 5. 의존 도메인 참고
- **Order**: `OrderItem.id`, `Order.status`, `Order.memberId`
- **Delivery**: `DeliveryCompletedEvent` → 리뷰 작성 가능 시점
- **Product**: `product_id` FK, 리뷰 요약 표시
