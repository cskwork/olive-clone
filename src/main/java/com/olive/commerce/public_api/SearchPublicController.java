package com.olive.commerce.public_api;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.product.ProductDtos;
import com.olive.commerce.search.AutocompleteService;
import com.olive.commerce.search.SearchDtos;
import com.olive.commerce.search.SearchPopularityAggregator;
import com.olive.commerce.search.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 검색 도메인 공개 API (OLV-101 / PRD §6.3, §8.1).
 *
 * <ul>
 *   <li>{@code GET /api/search/products}  — 키워드/카테고리/브랜드 + 정렬 + 페이지네이션.</li>
 *   <li>{@code GET /api/search/autocomplete} — prefix 자동완성.</li>
 *   <li>{@code GET /api/search/popular}   — 직전 1시간 인기검색어 TOP-N.</li>
 * </ul>
 *
 * <p>OpenSearch 장애 시 HTTP 503 {@code SEARCH_UNAVAILABLE} (메시지 "검색 일시 중단") —
 * {@link com.olive.commerce.common.error.BusinessException} 경유로 자동 envelope.
 */
@RestController
@RequestMapping("/api/search")
public class SearchPublicController {

    private final SearchService searchService;
    private final AutocompleteService autocompleteService;
    private final SearchPopularityAggregator popularityAggregator;

    public SearchPublicController(
        SearchService searchService,
        AutocompleteService autocompleteService,
        SearchPopularityAggregator popularityAggregator
    ) {
        this.searchService = searchService;
        this.autocompleteService = autocompleteService;
        this.popularityAggregator = popularityAggregator;
    }

    @GetMapping("/products")
    public ApiResponse<List<ProductDtos.PublicListItem>> products(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Long categoryId,
        @RequestParam(required = false) Long brandId,
        @RequestParam(required = false, defaultValue = "RELEVANCE") SearchDtos.SortOption sort,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = clamp(size, 1, 100, 20);
        SearchService.SearchResult result =
            searchService.searchProducts(keyword, categoryId, brandId, sort, safePage, safeSize);
        return ApiResponse.success(result.items(), result.meta());
    }

    @GetMapping("/autocomplete")
    public ApiResponse<SearchDtos.AutocompleteResponse> autocomplete(
        @RequestParam(required = false) String prefix,
        @RequestParam(defaultValue = "10") int size
    ) {
        int safeSize = clamp(size, 1, 20, 10);
        List<String> suggestions = autocompleteService.suggest(prefix, safeSize);
        return ApiResponse.success(new SearchDtos.AutocompleteResponse(suggestions));
    }

    @GetMapping("/popular")
    public ApiResponse<SearchDtos.PopularResponse> popular(
        @RequestParam(defaultValue = "10") int size
    ) {
        int safeSize = clamp(size, 1, 100, 10);
        return ApiResponse.success(new SearchDtos.PopularResponse(popularityAggregator.readTop(safeSize)));
    }

    private static int clamp(int v, int min, int max, int def) {
        if (v < min || v > max) return def;
        return v;
    }
}
