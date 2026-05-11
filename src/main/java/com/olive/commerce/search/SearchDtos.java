package com.olive.commerce.search;

import com.olive.commerce.product.ProductDtos;

import java.util.List;

/**
 * 검색 도메인 사용자 대면 DTO (PRD §6.3, §8.1, 티켓 OLV-101).
 *
 * <p>응답 모양은 클라이언트 재사용을 위해 {@link ProductDtos.PublicListItem}을 그대로 노출
 * — 검색은 단지 다른 데이터 소스(OpenSearch + DB hydration)일 뿐 모양은 같다.
 */
public sealed interface SearchDtos {

    /** 정렬 옵션. 기본은 RELEVANCE (= OpenSearch _score desc). */
    enum SortOption {
        RELEVANCE, POPULAR, LATEST, PRICE_ASC, PRICE_DESC, RATING
    }

    record AutocompleteResponse(List<String> suggestions) implements SearchDtos {}

    record PopularKeyword(String keyword, long count) implements SearchDtos {}

    record PopularResponse(List<PopularKeyword> keywords) implements SearchDtos {}
}
