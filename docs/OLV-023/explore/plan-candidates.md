# Plan Candidates - OLV-023

## 옵션 1: CategoryPublicService 모방 (단순 cache-aside)

**방식:**
- `ProductPublicService`에서 `StringRedisTemplate` 직접 사용
- Detail cache: `cache:product:detail:{id}`
- List cache: versioned key `cache:product:list:v{version}:{hash}`
- `ProductAdminService`에서 직접 cache 무효화

**장점:**
- 기존 Category 코드와 일관성
- 구현 간단, 의존성 최소

**단점:**
- Admin/Public이 강결합
- Event 기반 아키텍처 미준수 (PRD §96)

**파일:**
- `ProductPublicService.java` (신규)
- `ProductPublicController.java` (신규)
- `ProductDtos.java`에 `PublicListResponse`, `PublicDetailResponse` 추가
- `ProductAdminService.update()` 마지막에 cache 무효화 코드 추가

---

## 옵션 2: Spring Events + @EventListener (권장)

**방식:**
- `ProductUpdatedEvent` record 정의
- `ProductAdminService`에서 `ApplicationEventPublisher.publishEvent()`
- `ProductPublicService`에서 `@TransactionalEventListener(phase=AFTER_COMMIT)`로 수신
- Cache 무효화를 listener로 분리

**장점:**
- PRD §96 eventing 준수
- Admin/Public decoupling
- Transaction-after-commit 보장 (Spring이 처리)

**단점:**
- 코드량 약간 증가
- Event 클래스 추가

**파일:**
- `ProductUpdatedEvent.java` (신규, record)
- `ProductPublicService.java` (신규, listener 포함)
- `ProductPublicController.java` (신규)
- `ProductDtos.java`에 공개 DTO 추가
- `ProductAdminService`에 이벤트 발행 코드

---

## 옵션 3: Outbox 패턴 (미래 대비)

**방식:**
- `product_updated_outbox` 테이블에 이벤트 기록
- Batch worker가 Redis 무효화

**장점:**
- Redis 다운 시에도 이벤트 보존
- 최종적 일관성 보장

**단점:**
- 이 티켓 범위 초과
- 복잡도 과대

**결정:** 미보류 (OLV-096 eventing 티켓에서)

---

## 추천: 옵션 2 (Spring Events)

**이유:**
1. PRD §96 eventing 원칙 준수
2. AC2 "transaction-after-commit"을 `@TransactionalEventListener(phase=AFTER_COMMIT)`이 정확히 보장
3. 향후 Search index sync 등 다른 listener 추가 시 확장 가능
4. 코드량 증가는 미미함 (event record 1개 + @EventListener 메서드 1개)

**첫 번째 실패 테스트:**
```java
@Test
void getProductDetail_firstCallMissesSecondCallHitsCache() throws Exception {
    // First call - cache miss, populates Redis
    mockMvc.perform(get("/api/products/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(1));

    // Verify cache was populated
    String cached = redisTemplate.opsForValue().get("cache:product:detail:1");
    assertThat(cached).isNotNull();

    // Second call - should hit cache (<10ms in real system, here just verify no error)
    mockMvc.perform(get("/api/products/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(1));
}
```
