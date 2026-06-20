# Requirements: Skin Personalization (피부 맞춤 개인화)

<!-- supergoal SPEC phase 1/3 (requirements -> design -> tasks). Contract: reference/spec.md.
     WHAT, not HOW. Scope = portfolio candidates MDF-01 (Skin Profile & Personalized Discovery)
     + MDF-02 (Skin-Matched Reviews) from docs/spec/market-driven-features/goal.md.
     EARS keywords (WHEN/IF/AND/THEN/SHALL) kept in English as machine-checkable anchors;
     prose/glossary in Korean. Grounded in the existing review/member/home domains (2026-06-20). -->

## Introduction

현 시스템은 구매검증 리뷰·상품·검색·홈을 갖췄으나 개인화가 없다(홈 rail 정적, 멤버=익명 동일 화면; Member·Product에 피부 속성 없음). 본 기능은 **회원의 피부 프로파일(Skin Profile)**을 동의 기반으로 수집하고, 이를 **리뷰 신호(review-derived)**에 연결해 (1) "나와 같은 피부" 리뷰 필터·보조 평점과 (2) "내 피부 맞춤" 홈 rail을 제공한다. 상품에 피부 태그를 새로 다는 별도 데이터 작업 없이 **기존 구매검증 리뷰·`ProductReviewSummary` 집계 패턴을 재사용**해 매칭한다. 대상: 로그인 회원(개인화), 비로그인 쇼퍼(기존 동작 유지).

## Glossary

| Term | 정의 (Korean) |
|---|---|
| Member | 인증된 회원. 기존 `AuthenticatedUser(memberId, role)`로 식별. |
| Shopper | 상품/리뷰를 보는 사용자(비로그인 또는 Member). |
| Skin Profile | 회원이 **동의 하에** 저장한 피부 속성 집합: Skin Type(필수), Sensitivity, Skin Concern(0개 이상), Age Band(선택). 회원당 1개. |
| Skin Type | {DRY(건성), NORMAL(중성), OILY(지성), COMBINATION(복합성)} 중 정확히 1개. |
| Sensitivity | {SENSITIVE(민감성), NOT_SENSITIVE(비민감)} 중 1개. |
| Skin Concern | {PORES(모공), WRINKLES_ELASTICITY(주름/탄력), BRIGHTENING(미백/잡티), ACNE(트러블/여드름), REDNESS(홍조), DRYNESS(건조), KERATIN(각질)} 중 0개 이상(다중선택). |
| Age Band | 선택. {TEENS, 20S, 30S, 40S, 50S_PLUS} 중 1개. |
| Profile Consent | Skin Profile(민감정보) 저장·처리에 대한 회원의 명시적 동의(opt-in). |
| Purchase-Verified Review | 기존 개념. 배송완료(DELIVERED)된 본인 주문 항목에만 허용되는 리뷰. |
| Review Aggregate | 기존 개념. 상품별 전체 리뷰의 평균평점·리뷰수(`ProductReviewSummary`의 avgRating/reviewCount). |
| Reviewer Skin Snapshot | 리뷰 **제출 시점에** 작성자의 Skin Type/Sensitivity/Skin Concern을 복사해 그 리뷰에 고정한 값(이후 프로파일이 바뀌어도 불변). |
| Matched Reviewer Review | 보는 회원의 Skin Type과 **동일한** Reviewer Skin Snapshot을 가진 VISIBLE 리뷰. (MVP 매칭 기준 = Skin Type 일치) |
| Matched Rating | Matched Reviewer Review만의 평균평점. **Minimum Matched Sample 이상**일 때만 노출. |
| Minimum Matched Sample | Matched Rating을 노출하기 위한 최소 Matched Reviewer Review 수(기본값 5). |
| Skin-Matched Rail | 회원의 Skin Type 매칭 신호로 정렬된 홈의 "내 피부 맞춤" 상품 rail. |

## Requirements

### Requirement 1: 동의 기반 Skin Profile 생성

**User story:** As a Member, I want to record my Skin Type, Sensitivity, and Skin Concerns with my consent, so that the store can match reviews and products to my skin.

**Acceptance criteria (EARS):**
1. WHEN a Member submits a Skin Profile AND Profile Consent is granted THEN the system SHALL store the Skin Profile for that Member and return the stored profile.
2. IF a Member submits a Skin Profile without Profile Consent THEN the system SHALL reject the request and SHALL NOT store any skin attribute.
3. IF an unauthenticated Shopper attempts to create a Skin Profile THEN the system SHALL deny the request and require authentication.
4. WHEN a Member submits a Skin Profile AND the Skin Type is absent or not one of the defined Skin Type values THEN the system SHALL reject the request with a validation error.
5. WHEN a Member submits Skin Concerns AND any value is outside the Skin Concern taxonomy THEN the system SHALL reject the request with a validation error.

**Edge cases:**
- 빈 Skin Concern 목록 → 허용(Concern은 선택, Skin Type만 필수).
- Skin Concern 중복 값 → 거부가 아니라 de-dup 처리.
- Age Band 미제출 → 허용(선택).
- 이미 프로파일이 있는 회원의 재제출 → 오류가 아니라 Requirement 2의 갱신으로 처리.

### Requirement 2: Skin Profile 조회 및 갱신

**User story:** As a Member, I want to view and update my Skin Profile, so that matching reflects my current skin.

**Acceptance criteria (EARS):**
1. WHEN an authenticated Member with a stored Skin Profile requests their profile THEN the system SHALL return the current Skin Profile.
2. IF an authenticated Member without a stored Skin Profile requests their profile THEN the system SHALL return a "not set" result and SHALL NOT return an error.
3. WHEN a Member updates their Skin Profile AND Profile Consent is granted THEN the system SHALL replace the stored attributes and return the updated profile.
4. WHEN a Member's Skin Profile changes THEN the system SHALL NOT alter any previously stored Reviewer Skin Snapshot.

**Edge cases:**
- 필수 Skin Type 누락 갱신 → 거부.
- 동시 갱신 → 최종 상태는 하나의 일관된 프로파일이어야 함(충돌 처리는 design).

### Requirement 3: 동의 철회 및 Skin Profile 삭제

**User story:** As a Member, I want to withdraw consent and delete my Skin Profile, so that my sensitive skin data is removed.

**Acceptance criteria (EARS):**
1. WHEN a Member withdraws Profile Consent THEN the system SHALL delete that Member's Skin Profile and SHALL exclude the Member from all profile-based matching.
2. WHEN a Member has no Skin Profile (after deletion) AND views the home experience or a product THEN the system SHALL present non-personalized behavior (no Skin-Matched Rail, no Matched Rating).
3. IF a Member requests deletion when no Skin Profile exists THEN the system SHALL treat the request as successful (idempotent).

**Edge cases:**
- 삭제는 Reviewer Skin Snapshot 보존/삭제 정책에 영향(§Open questions 4) — 본 요구사항은 **라이브 프로파일** 삭제만 규정.

### Requirement 4: 리뷰 제출 시 Reviewer Skin Snapshot 부착

**User story:** As a Member writing a review, I want my skin attributes attached to that review, so that shoppers with my skin can find relevant reviews.

**Acceptance criteria (EARS):**
1. WHEN a Member submits a Purchase-Verified Review AND the Member has a stored Skin Profile THEN the system SHALL attach a Reviewer Skin Snapshot (Skin Type, Sensitivity, Skin Concern) to that review.
2. IF a Member submits a Purchase-Verified Review without a stored Skin Profile THEN the system SHALL store the review with no Reviewer Skin Snapshot AND SHALL still include it in the Review Aggregate.
3. WHEN a Reviewer Skin Snapshot is stored THEN it SHALL reflect the Member's Skin Profile at submission time AND SHALL remain unchanged when the Member later edits or deletes their Skin Profile.

**Edge cases:**
- 기존 구매검증 규칙(REVIEW_ELIGIBLE_ORDER_REQUIRED, REVIEW_ALREADY_EXISTS)은 변경 없이 그대로 선행 적용.
- Snapshot 없는 리뷰 → Matched Rating/필터에서 제외, 전체 Review Aggregate에는 포함.

### Requirement 5: "나와 같은 피부" 리뷰 필터

**User story:** As a Member with a Skin Profile, I want to filter a product's reviews to reviewers who share my Skin Type, so that I read relevant experiences.

**Acceptance criteria (EARS):**
1. WHEN an authenticated Member with a Skin Profile requests a product's reviews with the matched filter THEN the system SHALL return only Matched Reviewer Reviews for that product.
2. WHEN a Shopper requests a product's reviews without the matched filter THEN the system SHALL return all VISIBLE reviews (existing behavior unchanged).
3. IF a Shopper without a Skin Profile requests the matched filter THEN the system SHALL indicate that a Skin Profile is required AND SHALL NOT apply the filter.
4. WHEN the matched filter yields no Matched Reviewer Reviews THEN the system SHALL return an empty list AND SHALL NOT return an error.

**Edge cases:**
- 페이지네이션(page/size) 유지; HIDDEN 리뷰는 어떤 경우에도 미반환(기존 규칙).
- 매칭은 VISIBLE 리뷰만 대상.

### Requirement 6: "나와 같은 피부" 보조 평점 (상품 상세)

**User story:** As a Member with a Skin Profile, I want to see a rating from people who share my Skin Type on the product page, so that I judge fit beyond the overall average.

**Acceptance criteria (EARS):**
1. WHEN an authenticated Member with a Skin Profile views a product AND the count of Matched Reviewer Reviews is at least the Minimum Matched Sample THEN the system SHALL present the Matched Rating and the matched review count.
2. IF the count of Matched Reviewer Reviews is below the Minimum Matched Sample THEN the system SHALL NOT present a Matched Rating AND SHALL present the overall Review Aggregate.
3. IF a Shopper has no Skin Profile OR is unauthenticated THEN the system SHALL present only the overall Review Aggregate (existing behavior).
4. WHEN both are presented THEN the overall Review Aggregate SHALL remain visible (the Matched Rating is additive, not a replacement).

**Edge cases:**
- 리뷰 0개 상품 → 어떤 평점도 미노출(기존 동작).
- Matched 수가 정확히 임계값 → 노출(경계 포함).
- 평점 표기 형식은 기존 avgRating(소수 둘째 자리) 규약과 일치.

### Requirement 7: "내 피부 맞춤" 홈 rail (fallback 포함)

**User story:** As a Member with a Skin Profile, I want a "내 피부 맞춤" rail on the home page, so that I discover products suited to my skin.

**Acceptance criteria (EARS):**
1. WHEN an authenticated Member with a Skin Profile loads the home experience THEN the system SHALL return a Skin-Matched Rail of products ranked by the matched-reviewer signal for the Member's Skin Type, alongside the existing rails.
2. IF an authenticated Member has no Skin Profile THEN the system SHALL omit the Skin-Matched Rail AND SHALL still return the existing non-personalized rails.
3. IF the Shopper is unauthenticated THEN the system SHALL NOT return a Skin-Matched Rail AND SHALL return the existing rails unchanged.
4. IF fewer products qualify than the defined minimum rail size THEN the system SHALL omit the Skin-Matched Rail rather than present mislabeled items.

**Edge cases:**
- 신규 회원·매칭 데이터 없음 → rail 생략, 오류 없음.
- 프로파일은 있으나 매칭 상품 부족 → rail 생략(거짓 "맞춤" 라벨 금지).

## Non-functional requirements

- **Performance:** Skin-Matched Rail(홈)과 Matched Rating(상품 상세) 응답은 동시 회원 100명 기준 **p95 < 500ms**. (필요 시 매칭 신호 사전집계 — design 사항)
- **Security/Privacy:** Skin Profile은 민감 개인정보 → **Profile Consent 있을 때만** 저장; 소유 회원만 조회·수정·삭제 가능; 삭제 요청 시 라이브 프로파일 제거. Reviewer Skin Snapshot은 리뷰에 **거친 Skin Type 라벨**로만 표시하고 회원 식별자(email 등)와 연결해 노출하지 않음. 프로파일 접근은 기존 audit 규약을 따른다.
- **Compatibility:** 기존 public 리뷰 API와 상품 상세 응답은 하위호환 유지(신규 필드는 additive·optional). 비로그인/무프로파일 경로의 기존 동작은 불변.
- **Accessibility:** 신규 UI(프로파일 입력, 매칭 필터 컨트롤, rail, 보조 평점 표시)는 **WCAG 2.1 AA**(명도대비·키보드·스크린리더 라벨) 충족, 기존 디자인 토큰과 일관.

## Out of scope

- 셀피/카메라 기반 AI 피부진단(goal.md Watchlist W3).
- 상품 단위 피부 속성 태깅 및 피부고민 검색 facet — 본 기능의 매칭은 **리뷰 신호 기반**이며 상품 태깅은 별도 데이터 작업.
- ML 추천엔진/협업필터링 — Skin-Matched Rail은 리뷰 신호 기반 규칙.
- 매칭 기준에 Sensitivity/Skin Concern 포함 — MVP는 **Skin Type 일치만** 사용.
- goal.md의 타 후보(MDF-03 성분 등).

## Open questions

1. 최종 Skin Type/Skin Concern/Age Band 분류·한글 라벨 — 머천다이징과 확정 필요.
2. Minimum Matched Sample 기본값(현 5) — 실제 리뷰 데이터량에 맞춰 튜닝.
3. 매칭 기준 확장 시점 — Skin Type only(MVP) → Sensitivity/Concern 가중 언제?
4. 프로파일 삭제 후 Reviewer Skin Snapshot 처리 — 게시된 리뷰에 보존 vs 함께 purge(개인정보 정책 결정).
5. Skin-Matched Rail 희소 시 — 생략(현 안) vs 백필+정확 라벨링.
6. Age Band 수집 여부 — 민감성 고려해 선택 유지 vs MVP에서 제외.
