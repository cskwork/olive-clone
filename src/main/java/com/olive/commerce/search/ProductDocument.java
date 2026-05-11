package com.olive.commerce.search;

import java.util.List;

/**
 * OpenSearch {@code products} 인덱스의 단일 문서 (PRD §13.2, wiki §95-search-domain).
 *
 * <p>이 record가 곧 색인되는 JSON 모양이다. {@code _id}는 {@link #productId()}.
 * tags는 도메인에 아직 별도 테이블이 없어 항상 빈 리스트(향후 ticket에서
 * 활성화). rating/salesCount/reviewCount도 같은 이유로 0 디폴트.
 */
public record ProductDocument(
    Long productId,
    String productName,
    String brandName,
    List<String> categoryNames,
    List<String> tags,
    Long salePrice,
    Float rating,
    Long salesCount,
    Long reviewCount,
    String status
) {
    public static final String INDEX_NAME = "products";
}
