# OLV-101 QA Evidence

## 실행 명령어

```bash
# 전체 검색 관련 테스트 실행
cd /Users/danny/Documents/PARA/Resource/olive-clone
./gradlew test --tests "com.olive.commerce.public_api.*ApiIT" \
               --tests "com.olive.commerce.search.*IT"
```

**결과**: BUILD SUCCESSFUL in 51s

## Acceptance Criteria 검증

| AC | 설명 | 테스트 | 결과 |
|----|------|------|------|
| AC1 | keyword=선크림 → ON_SALE products, relevance 정렬 | SearchApiIT#ac1_keyword_선크림_returnsOnSaleProducts | PASS |
| AC2 | categoryId + keyword → intersection | SearchApiIT#ac2_keywordAndCategoryFilter_returnsIntersection | PASS |
| AC2 | 존재하지 않는 category → empty | SearchApiIT#ac2_keywordAndNonMatchingCategoryFilter_returnsEmpty | PASS |
| AC3 | OpenSearch 다운 → 503 "검색 일시 중단" | SearchApiIT#ac3_openSearchDown_returns503WithDocumentedBody | PASS |
| AC4 | autocomplete prefix 매칭 (case-insensitive) | AutocompleteApiIT#ac4_korean_prefix_returnsMatches | PASS |
| AC4 | 영문 prefix case-insensitive | AutocompleteApiIT#ac4_english_prefix_isCaseInsensitive | PASS |
| AC5 | 100회 검색 후 인기검색어 non-empty | PopularKeywordsApiIT#ac5_seed100Searches_thenPopularEndpointReturnsNonEmpty | PASS |

## 테스트 로그

```
SearchApiIT > ac1_keyword_선크림_returnsOnSaleProducts() PASSED
SearchApiIT > ac2_keywordAndCategoryFilter_returnsIntersection() PASSED
SearchApiIT > ac2_keywordAndNonMatchingCategoryFilter_returnsEmpty() PASSED
SearchApiIT > ac3_openSearchDown_returns503WithDocumentedBody() PASSED

AutocompleteApiIT > ac4_korean_prefix_returnsMatches() PASSED
AutocompleteApiIT > ac4_english_prefix_isCaseInsensitive() PASSED

PopularKeywordsApiIT > ac5_seed100Searches_thenPopularEndpointReturnsNonEmpty() PASSED
```

## 수정 사항

### application-test.yml

테스트 실행 중 발견된 누락 설정을 추가했습니다:

```yaml
aws:
  s3:
    region: us-east-1  # 추가: S3Client 빈 생성에 필요

olive:
  security:
    jwt:
      issuer: olive-commerce-test        # 추가
      access-ttl: PT30M                   # 추가
      refresh-ttl: P14D                   # 추가
      private-key-location: classpath:keys/app.key   # 추가
      public-key-location: classpath:keys/app.pub     # 추가
```

이 설정들은 Search API 직접 관련은 아니지만, `@SpringBootTest`가 전체 context를 로드하므로 필요합니다.
