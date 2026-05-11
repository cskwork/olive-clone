package com.olive.commerce.search;

import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.product.Brand;
import com.olive.commerce.product.BrandRepository;
import com.olive.commerce.product.Category;
import com.olive.commerce.product.CategoryRepository;
import com.olive.commerce.product.Product;
import com.olive.commerce.product.ProductDtos;
import com.olive.commerce.product.ProductImageRepository;
import com.olive.commerce.product.ProductRepository;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.MultiMatchQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 키워드 검색 (OLV-101 / PRD §6.3, §8.1).
 *
 * <p>흐름: 사용자 입력 → OpenSearch bool query (must=multi_match, filter=status/category/brand)
 * → hit ID 목록 → DB hydration → {@link ProductDtos.PublicListItem} 응답 모양.
 * OS 호출 실패는 모두 {@link BusinessException}({@link ErrorCode#SEARCH_UNAVAILABLE})로 매핑 —
 * wiki §99-failure-handling §15.3 정책. DB LIKE fallback은 금지.
 *
 * <p>keyword가 비어 있지 않은 호출은 {@link SearchPopularityRecorder}로 누적된다.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final OpenSearchClient client;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    private final SearchPopularityRecorder popularityRecorder;

    public SearchService(
        OpenSearchClient client,
        ProductRepository productRepository,
        ProductImageRepository productImageRepository,
        BrandRepository brandRepository,
        CategoryRepository categoryRepository,
        SearchPopularityRecorder popularityRecorder
    ) {
        this.client = client;
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.brandRepository = brandRepository;
        this.categoryRepository = categoryRepository;
        this.popularityRecorder = popularityRecorder;
    }

    public SearchResult searchProducts(
        String keyword,
        Long categoryId,
        Long brandId,
        SearchDtos.SortOption sort,
        int page,
        int size
    ) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        SearchDtos.SortOption effectiveSort = sort != null ? sort : SearchDtos.SortOption.RELEVANCE;

        Query query = buildQuery(normalizedKeyword, categoryId, brandId);

        SearchRequest request = SearchRequest.of(b -> {
            b.index(ProductDocument.INDEX_NAME)
                .query(query)
                .from(page * size)
                .size(size)
                .source(s -> s.filter(f -> f.includes("productId")))
                .trackTotalHits(t -> t.enabled(true));
            applySort(b, effectiveSort);
            return b;
        });

        SearchResponse<ProductDocument> response;
        try {
            response = client.search(request, ProductDocument.class);
        } catch (OpenSearchException | IOException e) {
            log.warn("Search query failed (keyword={}, categoryId={}, brandId={})",
                normalizedKeyword, categoryId, brandId, e);
            throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE, "검색 일시 중단");
        }

        // popularity 기록은 OS 성공 호출일 때만 — 실패한 쿼리는 사용자 의도 불명확.
        if (!normalizedKeyword.isEmpty()) {
            popularityRecorder.record(normalizedKeyword);
        }

        List<Long> hitIds = new ArrayList<>(response.hits().hits().size());
        for (Hit<ProductDocument> hit : response.hits().hits()) {
            if (hit.id() != null) {
                hitIds.add(Long.valueOf(hit.id()));
            }
        }

        long total = response.hits().total() != null ? response.hits().total().value() : 0L;
        List<ProductDtos.PublicListItem> items = hydrate(hitIds);
        PageMeta meta = new PageMeta(page, size, total);
        return new SearchResult(items, meta);
    }

    private Query buildQuery(String keyword, Long categoryId, Long brandId) {
        List<Query> mustClauses = new ArrayList<>();
        List<Query> filterClauses = new ArrayList<>();

        if (!keyword.isEmpty()) {
            mustClauses.add(Query.of(q -> q.multiMatch(MultiMatchQuery.of(m -> m
                .query(keyword)
                .fields("productName^2", "tags^1.5", "brandName")
            ))));
        }

        // status=ON_SALE 항상 강제 — 본 엔드포인트는 사용자 대면.
        filterClauses.add(Query.of(q -> q.term(t -> t
            .field("status")
            .value(v -> v.stringValue("ON_SALE"))
        )));

        if (categoryId != null) {
            String name = categoryRepository.findById(categoryId)
                .map(Category::getName)
                .orElse(null);
            if (name == null) {
                // 존재하지 않는 카테고리 → 결과 0 보장하는 사실상-매치-안되는 term.
                filterClauses.add(Query.of(q -> q.term(t -> t
                    .field("categoryNames")
                    .value(v -> v.stringValue("__no_such_category__"))
                )));
            } else {
                filterClauses.add(Query.of(q -> q.term(t -> t
                    .field("categoryNames")
                    .value(v -> v.stringValue(name))
                )));
            }
        }
        if (brandId != null) {
            String name = brandRepository.findById(brandId)
                .map(Brand::getName)
                .orElse(null);
            if (name == null) {
                filterClauses.add(Query.of(q -> q.term(t -> t
                    .field("brandName")
                    .value(v -> v.stringValue("__no_such_brand__"))
                )));
            } else {
                filterClauses.add(Query.of(q -> q.term(t -> t
                    .field("brandName")
                    .value(v -> v.stringValue(name))
                )));
            }
        }

        return Query.of(q -> q.bool(b -> b
            .must(mustClauses)
            .filter(filterClauses)
        ));
    }

    private void applySort(SearchRequest.Builder b, SearchDtos.SortOption sort) {
        switch (sort) {
            case RELEVANCE -> b.sort(s -> s.score(sc -> sc.order(SortOrder.Desc)));
            case POPULAR -> b.sort(s -> s.field(f -> f.field("salesCount").order(SortOrder.Desc)));
            case LATEST -> b.sort(s -> s.field(f -> f.field("productId").order(SortOrder.Desc)));
            case PRICE_ASC -> b.sort(s -> s.field(f -> f.field("salePrice").order(SortOrder.Asc)));
            case PRICE_DESC -> b.sort(s -> s.field(f -> f.field("salePrice").order(SortOrder.Desc)));
            case RATING -> b.sort(s -> s.field(f -> f.field("rating").order(SortOrder.Desc)));
        }
    }

    private List<ProductDtos.PublicListItem> hydrate(List<Long> productIds) {
        if (productIds.isEmpty()) return List.of();

        List<Product> products = productRepository.findAllById(productIds);
        Map<Long, Product> byId = new HashMap<>();
        for (Product p : products) byId.put(p.getId(), p);

        Map<Long, String> thumbByProduct = new HashMap<>();
        for (Object[] row : productImageRepository.findThumbnailUrlsByProductIds(productIds)) {
            Long pid = ((Number) row[0]).longValue();
            String url = (String) row[1];
            thumbByProduct.put(pid, url);
        }

        // OpenSearch가 돌려준 순서를 그대로 유지.
        List<ProductDtos.PublicListItem> out = new ArrayList<>(productIds.size());
        for (Long id : productIds) {
            Product p = byId.get(id);
            if (p == null) continue; // 삭제된 product. 다음 인덱서 tick에서 OS에서 제거됨.
            BigDecimal sale = p.getSalePrice() != null ? p.getSalePrice() : p.getBasePrice();
            BigDecimal original = p.getBasePrice();
            BigDecimal discount = calculateDiscount(original, sale);
            out.add(new ProductDtos.PublicListItem(
                p.getId(),
                p.getBrand() != null ? p.getBrand().getName() : null,
                p.getName(),
                sale,
                original,
                discount,
                thumbByProduct.get(id),
                BigDecimal.ZERO,
                0
            ));
        }
        return out;
    }

    private static BigDecimal calculateDiscount(BigDecimal original, BigDecimal sale) {
        if (original == null || original.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return original.subtract(sale)
            .divide(original, 4, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .setScale(1, java.math.RoundingMode.HALF_UP);
    }

    public record SearchResult(List<ProductDtos.PublicListItem> items, PageMeta meta) {}
}
