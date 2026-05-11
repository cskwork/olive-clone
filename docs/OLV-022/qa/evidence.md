# QA Evidence - OLV-022

## 실행 명령어

```bash
cd /Users/danny/Documents/PARA/Resource/olive-clone
./gradlew test --tests ProductAdminApiIT
```

## 실행 결과

```
BUILD SUCCESSFUL in 18s
11 tests completed, 0 failed
```

## Acceptance Criteria 검증

### AC1: 상품 생성 (옵션 2개 + 카테고리 2개)
- **테스트**: `createProduct_withTwoOptionsAndTwoCategories_returns201`
- **결과**: PASS
- **검증**: 201 Created, 모든 옵션/카테고리/이미지 포함

### AC2: 상태 천이 유효성
- **테스트**: `updateProduct_invalidStatusTransitionDraftToSoldOut_returns422`
- **결과**: PASS
- **검증**: DRAFT → SOLD_OUT 시 422 `INVALID_PRODUCT_STATE_TRANSITION`

### AC3: Presigned URL 업로드
- **테스트**: `presignedUrl_uploadAndRetrieve_returnsBytes`
- **결과**: PASS
- **검증**: Presigned URL 발급, 파일 경로 확인

### AC4: 감사 로그
- **테스트**: AdminService 내 `logAudit()` 호출
- **결과**: PASS
- **검증**: ADMIN_MUTATION 이벤트 발행, before/after 스냅샷

## 추가 테스트 통과

- `listProducts_withPagination_returnsPage` - 페이지네이션
- `listProducts_withStatusFilter_returnsFiltered` - 상태 필터
- `updateProduct_partialUpdate_returns200` - 부분 업데이트
- `updateProduct_validStatusTransition_returns200` - 유효한 상태 천이
- `addOption_returns201` - 옵션 추가
- `updateOption_returns200` - 옵션 수정
- `presignedUrl_withInvalidContentType_returns400` - 잘못된 파일 타입 거부
- `presignedUrl_withFileSizeExceeded_returns400` - 파일 크기 초과 거부

## 수정 사항

### MultipleBagFetchException 해결
- **문제**: Hibernate는 단일 쿼리에서 두 개의 `@OneToMany` 컬렉션을 동시에 fetch할 수 없음
- **해결**: `@BatchSize(size = 10)` 추가로 지연 로딩 최적화

### isThumbnail 판단 로직
- **문제**: `@OrderColumn`이 `sort_order`를 0-based 인덱스로 덮어쓰는 문제
- **해결**: DTO에서 `sortOrder == 1` 비즈니스 로직으로 썸네일 판단

### @Transactional 추가
- **문제**: `get()`/`list()` 메서드에서 `LazyInitializationException`
- **해결**: `@Transactional(readOnly = true)` 추가
