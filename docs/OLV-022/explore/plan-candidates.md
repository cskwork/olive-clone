# OLV-022 Explore: Plan Candidates

## Option A — 단일 Service + 분리 컨트롤러 (권장)

**구조**:
- `Product.java`, `ProductOption.java`, `ProductImage.java` Entity
- `ProductRepository`, `ProductOptionRepository`, `ProductImageRepository`
- `ProductAdminService.java` — 상품 CRUD + 옵션 관리 + 상태 천이 검증
- `ProductUploadService.java` — Presigned URL 발급 (S3Client 직접 사용)
- `ProductAdminController.java` — `/api/admin/products/*` 엔드포인트
- `ProductUploadController.java` — `/api/admin/uploads/product-image` 엔드포인트

**장점**:
- 도메인 로직이 Service에 집중 → 테스트 용이
- Presigned URL 발급이 분리되어 S3 의존성이 격리됨
- 감사 로그를 Service 계층에서 통합 관리

**단점**:
- 클래스 수가 많음 (Entity 3 + Repository 3 + Service 2 + Controller 2)
- 트랜잭션 범위를 명확히 해야 함 (옵션/카테고리/이미지 동시 생성)

---

## Option B — Product Service에 Upload 통합

**구조**:
- Option A와 동일하나 `ProductUploadService`를 `ProductAdminService`에 통합
- Presigned URL 발급이 `ProductAdminService.getPresignedUrl()`로 이동

**장점**:
- 클래스 수 감소 (Service 1개)
- 상품 관련 로직이 한 곳에 집중

**단점**:
- S3 의존성이 상품 Service에 섞임 (단일 책임 원칙 위반)
- Presigned URL은 상품 생성 이전에 호출됨 (생명주기가 다름)
- 테스트 시 S3 모킹이 필수 (상품 CRUD만 테스트하기 어려움)

---

## Option C — Controller에서 S3Client 직접 호출

**구조**:
- Service는 DB 로직만 담당
- Controller에서 `@Autowired S3Client`를 직접 호출하여 presigned URL 생성

**장점**:
- Service가 순수 도메인 로직에 집중
- Presigned URL 발급이 DB 트랜잭션과 분리

**단점**:
- 비즈니스 로직이 Controller로 누출 (S3 key prefix, 파일 확장자 검증 등)
- 테스트가 Controller까지 띄워야 함 (@WebMvcTest 불가)
- S3 설정 변경 시 Controller 수정 필요

---

## Option D — Facade Pattern (도입 과잉)

**구조**:
- `ProductAdminFacade`가 `ProductAdminService` + `ProductOptionService` + `CategoryService` + `UploadService`를 조합
- 트랜잭션과 감사 로그를 Facade에서 관리

**장점**:
- 각 Service가 단일 책임을 유지
- Facade에서 오케스트레이션 로직이 명확함

**단점**:
- 현재 티켓 범위에는 과잉 설계 (도메인이 복잡하지 않음)
- 클래스 수 급증 (+2)
- 향후 확장성이 필요하지 않으면 불필요한 복잡도
