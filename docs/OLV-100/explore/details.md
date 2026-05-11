# OLV-100 Explore 세부 노트

## 기존 자산 (재사용 대상)

- `common/config/OpenSearchConfig.java:27-46` — `OpenSearchClient` 빈
  (legacy RestClient + `JacksonJsonpMapper`). 본 티켓의 인덱서는 이 빈을
  주입받아 `bulk()` 호출.
- `product/ProductUpdatedEvent.java:11-18` — record `(Long productId,
  Instant occurredAt)`. 이미 `ProductAdminService.create/update/addOption/
  updateOption` 4곳에서 발행됨(`ProductAdminService.java:126/209/236/263`).
- `product/Product.java:148-158` — getter 노출. brand/options/images는
  지연 로딩(`@BatchSize(10)`). 인덱싱 시 brand 이름 + 카테고리 이름이
  필요하므로 `EntityManager` 네이티브 join 또는 별도 fetch 쿼리 권장.
- `product/ProductRepository.java:30-31` — `findByIdWithDetails`는 brand
  + images만 fetch. 인덱서는 brand만 fetch + 카테고리는 매핑 테이블
  네이티브 쿼리로 조회(`ProductAdminService.list`의 패턴 재사용).

## 누락된 자산 (신규 생성 필요)

1. **Flyway V8 — `outbox_events` 테이블**
   - 컬럼: `id BIGSERIAL`, `aggregate_type VARCHAR(50)`,
     `aggregate_id BIGINT`, `event_type VARCHAR(50)`, `payload_json TEXT`,
     `status VARCHAR(20) NOT NULL DEFAULT 'PENDING'`, `attempt_count INT
     NOT NULL DEFAULT 0`, `dlq BOOLEAN NOT NULL DEFAULT FALSE`,
     `last_error TEXT`, `created_at TIMESTAMPTZ`, `processed_at
     TIMESTAMPTZ`.
   - 인덱스: 드레이너 스캔용 `(status, dlq, attempt_count, id)`.
   - wiki 96-eventing 정의(`(id, aggregate_type, aggregate_id,
     event_type, payload_json, status, created_at, processed_at)`)를
     확장하여 attempt_count/dlq/last_error 추가.

2. **JPA 엔티티 + Repository**
   - `OutboxEvent` 엔티티 (immutable update via 도메인 메서드만).
   - `OutboxEventRepository` JpaRepository — `findTopNPending`
     스캔 쿼리.

3. **`ProductDocument` (인덱싱 페이로드)**
   - Record. 9 필드: productId, productName, brandName,
     categoryNames(List<String>), tags(List<String>=empty),
     salePrice(Long), rating(Float=0.0), salesCount(Long=0),
     reviewCount(Long=0), status(String).

4. **OpenSearch 인덱스 매핑 부트스트랩**
   - `SearchIndexInitializer`(`@Component` + `ApplicationRunner` or
     `@PostConstruct`): 부팅 시 `products` 인덱스 존재 확인 → 없으면
     매핑/세팅 포함 생성. 매핑은 wiki 95-search-domain 스펙대로:
     productName(text + Korean analyzer try-fallback),
     tags/categoryNames/brandName/status(keyword), salePrice/salesCount
     /reviewCount(long), rating(float).
   - nori 분석기 미설치 환경은 `standard`로 fallback (도커 이미지
     기본은 nori 미포함, 코드에서 try-create 후 실패 시 standard로
     재시도하지 않고 — 단순화 — `standard` 고정 + nori 검증은 follow-up
     티켓으로 문서화).

5. **`ProductIndexer` (인덱싱 서비스)**
   - `indexById(Long productId)` — DB에서 product 조회 → `ProductDocument`
     변환 → bulk index.
   - `indexBulk(List<Long> productIds)` — 100건 단위 BulkRequest.
   - 삭제된 product는 `DeleteRequest`로 OS에서 제거 (DB lookup이 빈 경우).

6. **Outbox 발행 훅**
   - 기존 4곳 `eventPublisher.publishEvent(new ProductUpdatedEvent(id))`
     호출의 같은 트랜잭션에서 `OutboxEventRepository.save(...)`로
     `PRODUCT_INDEX_SYNC` 행 insert.
   - 같은 트랜잭션 insert → 커밋 후 드레이너가 픽업: outbox 패턴
     정석(wiki 96-eventing §Producer pattern). cache 무효화 listener는
     그대로 두어 캐시 동기화는 분리 유지.

7. **`OutboxIndexerWorker`**
   - `@Scheduled(fixedDelay = 1000)` (`@EnableScheduling`을
     `CommerceBackendApplication`에 추가).
   - 트랜잭션 1: status='PENDING' AND dlq=false AND attempt_count<5
     상위 100건 SELECT FOR UPDATE SKIP LOCKED, status='IN_PROGRESS'로 마크.
   - OpenSearch bulk index 실행.
   - 트랜잭션 2: 성공한 행은 status='DONE', processed_at=now();
     실패한 행은 attempt_count+1, status 다시 'PENDING' (재시도 가능
     상태); attempt_count == 5 도달 시 dlq=true.
   - OpenSearch 예외(`OpenSearchException`, IOException, connection
     refused 등)는 잡아 로깅만, 워커는 다음 tick 재시도.

8. **`ReindexProductsCommand`**
   - `ApplicationRunner` + 프로필 가드 (`spring.profiles.active=reindex` 또는
     커맨드라인 인자 `--reindex-products`).
   - `./gradlew reindexProducts`는 `bootRun --args='--reindex-products'`로
     매핑하는 별도 Gradle task.
   - 또는 단순히 `JavaExec` task 만들어 `--spring.profiles.active=reindex`
     + main runner 동작. (구현 시 가장 단순한 형태 선택)

9. **관리자 재인덱스 엔드포인트**
   - `POST /api/admin/search/reindex/{productId}` —
     `SearchAdminController` 신규 추가. 권한 `PRODUCT_ADMIN`.
   - 동작: outbox에 PRODUCT_INDEX_SYNC 한 건 enqueue 후 202 반환.
     (직접 동기 호출이 아니라 같은 파이프라인 사용 — 시간 분포/장애
     처리 통일.)

## 리스크 / 알려진 함정

- **MultipleBagFetchException**: `Product` 엔티티는 options + images
  두 `@OneToMany` 컬렉션. 인덱서에서 동시에 fetch하면 터짐
  (`wiki/20-product-domain.md §Hibernate Performance`). 인덱서는
  brand만 fetch하고 카테고리는 네이티브 쿼리로 분리 — fetch 충돌 회피.
- **트랜잭션 경계**: outbox 행 insert와 product 변경은 같은 트랜잭션.
  드레이너는 별도 트랜잭션에서 작동. `@Scheduled` 메서드 자체에
  `@Transactional` 두 단계 분리 (claim → process → mark) 또는
  `TransactionTemplate` 사용.
- **OpenSearch 다운 시 무중단**: 위에 명시한 try/catch + 워커가 죽지
  않게 한다(`@Scheduled`는 메서드 throw 시 다음 fire 차단 X — Spring
  ScheduledTaskRegistrar는 단순히 다음 fixedDelay 호출 큐잉. 그래도
  명시적으로 try/catch + log로 처리).
- **테스트 환경**: `PostgresIntegrationSupport`(JVM-싱글톤 Postgres) +
  `OpensearchContainer` 결합. opensearch-testcontainers의 lifecycle은
  JUnit `@Testcontainers`에 의존. 이미 OLV-003에서 검증된 패턴 재사용.
- **Idempotency**: outbox row가 두 번 처리될 수 있다 — bulk index는
  `_id`를 productId로 사용 → upsert 의미(같은 doc 재인덱스는 안전).

## 결정 사항 후보

- **nori 분석기**: 기본 이미지에 미포함. 본 티켓은 `standard` 분석기
  고정 — wiki 데시전 로그에 한국어 검색 품질 한계 follow-up으로 적시.
- **outbox 파티셔닝**: 단일 테이블 + 인덱스 만으로 시작(MVP).
  파티셔닝/별도 큐 분리는 트래픽 확인 후.
- **재시도 한도**: 5회. dlq=true 행은 관리자가 별도 endpoint로
  flag 해제 후 재처리(본 티켓은 dlq enable까지만, 해제 UI는
  follow-up).
