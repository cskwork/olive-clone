# OLV-022 Explore: Domain Brief

## 무엇
상품 관리자 CRUD API와 S3 presigned URL 이미지 업로드 기능을 구현한다. Admin UI가 서버를 프록시 없이 S3에 직접 업로드하도록 presigned URL을 발급하고, 모든 변경 시 감사 로그를 남긴다.

## 왜
PRD §3.2 first-class admin requirement로 판매 가능한 모든 것의 기반이다. 이미지를 앱 서버를 통해 프록시하지 않고 S3에 직접 업로드하면 트래픽 부하가 줄어들고, 관리자의 모든 변경을 감사 로그로 추적할 수 있다.

## As-Is → To-Be
- **As-Is**: V3__product.sql에 테이블만 존재. Entity/Repository/Service/Controller 없음. Presigned URL 발급 기능 없음. AdminProductPlaceholderController만 있음.
- **To-Be**: Product/ProductOption/ProductImage Entity 3개 + Repository 3개 + ProductAdminService + Admin 컨트롤러 2개(Product + Upload) + ErrorCode 확장 + 감사 로그 통합 + IT 3건 이상.

## 핵심 인바리언트

### 상품 상태 천이 (Product Status Transition)
```
DRAFT ──────→ ON_SALE
                  ↓
              SOLD_OUT ←─────→ (다시 ON_SALE)
                  ↓
              STOPPED

HIDDEN (언제든지 가능)
```
- DRAFT → ON_SALE (초기 판매 시작)
- ON_SALE ↔ SOLD_OUT (재고 소진/복구)
- ON_SALE → STOPPED (판매 중지)
- HIDDEN (모든 상태에서 가능, 사용자 화면에서 숨김)

### 트랜잭션 범위
- `POST /api/admin/products`: `@Transactional`로 products + product_options + product_category_mapping + product_images를 원자적으로 생성.
- 일부 실패 시 전체 롤백.

### 돈 (Money)
- `base_price`, `sale_price`는 PostgreSQL `DECIMAL(12,2)` → Java `BigDecimal`
- 음수 가격 입력 시 400 Bad Request

### 이미지 업로드 흐름
1. Admin UI → `POST /api/admin/uploads/product-image` (fileSize, contentType)
2. Backend → S3 Presigned URL 생성 → `{uploadUrl, fileUrl}` 반환
3. Admin UI → `PUT {uploadUrl}` with image bytes
4. Admin UI → `POST /api/admin/products` with `image_urls: [fileUrl, ...]`

### 감사 로그 (Audit Log)
- `AuditLogger.log("ADMIN_MUTATION", Map.of("memberId", id, "entity", "Product", "action", "CREATE/UPDATE", "before", json, "after", json))`
- 변경 전후 스냅샷을 JSON으로 기록

## 기존 패턴 분석

### Brand/Category Admin 패턴 (OLV-021)
1. **Entity**: JPA 매핑 + factory method + validation
2. **Repository**: `JpaRepository` + 커스텀 쿼리 (`@Query`)
3. **Dtos**: sealed interface + record (CreateRequest/UpdateRequest/Response)
4. **Service**: `@Transactional` + `BusinessException` + EntityManager flush/refresh
5. **Controller**: `@PreAuthorize("hasRole('PRODUCT_ADMIN')")` + `ApiResponse<T>`

### S3 Configuration (OLV-003)
- `S3Client` 빈 이미 존재 (`common.config.AwsS3Config`)
- LocalStack mode: `endpoint=http://localhost:4566`
- Presigned URL: `s3Client.utilities().getPresignedPutUrl(...)`

### Audit Logger (OLV-004)
- `AuditLogger` 인터페이스 + `LogbackAuditLogger` 구현
- 주입해서 사용: `auditLogger.log("ADMIN_MUTATION", attrs)`

## 위험 요소

### R1: 상태 천이 유효성 검증
- 허용되지 않은 천이 (예: DRAFT → REFUNDED)는 422 Unprocessable Entity
- `ProductStatus` enum + `isValidTransition()` 메서드로 검증

### R2: 옵션과 카테고리 동시 생성
- `POST /api/admin/products`에서 옵션 2개 + 카테고리 2개를 동시에 생성
- `product_options`와 `product_category_mapping`을 같은 트랜잭션으로 처리

### R3: Presigned URL 보안
- 파일 크기 제한 없으면 DOS 공격 가능
- 파일 타입 검증 없으면 악성 파일 업로드 가능
- 해결: request에 `fileSize`와 `contentType`을 받아 검증

### R4: 감사 로그의 JSON 직렬화
- Entity → JSON 변환 시 순환 참조 위험
- 해결: Jackson `@JsonIgnore` 또는 DTO로 변환 후 직렬화

## 의존 파일

- `llm-wiki/20-product-domain.md`: products/product_options/product_images 테이블 구조, 상태 enum
- `llm-wiki/01-common-conventions.md`: ApiResponse envelope, ErrorCode 매핑, 감사 로그 규약
- `llm-wiki/03-infra-baseline.md`: S3Client 빈, LocalStack mode, presigned URL 패턴
- `V3__product.sql`: products/product_options/product_images/product_category_mapping 테이블 정의
- PRD §6.2, §7.2: 상품 도메인, 상태 천이 규칙
