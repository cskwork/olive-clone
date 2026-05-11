package com.olive.commerce.public_api;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.product.ProductDtos;
import com.olive.commerce.product.ProductPublicService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 공개 상품 API 컨트롤러 (OLV-023).
 *
 * GET /api/products - 목록 (페이지네이션, 필터, 정렬)
 * GET /api/products/{id} - 상세
 */
@RestController
@RequestMapping("/api/products")
public class ProductPublicController {

    private final ProductPublicService productPublicService;

    public ProductPublicController(ProductPublicService productPublicService) {
        this.productPublicService = productPublicService;
    }

    /**
     * 상품 목록 조회.
     *
     * @param categoryId 카테고리 필터 (선택)
     * @param brandId 브랜드 필터 (선택)
     * @param sort 정렬: popular, latest, price_asc, price_desc, rating (기본 latest)
     * @param page 페이지 번호 (0-based, 기본 0)
     * @param size 페이지 크기 (기본 20)
     * @return 상품 목록 (HIDDEN/STOPPED/DRAFT 제외)
     */
    @GetMapping
    public ApiResponse<List<ProductDtos.PublicListItem>> list(
        @RequestParam(required = false) Long categoryId,
        @RequestParam(required = false) Long brandId,
        @RequestParam(required = false, defaultValue = "LATEST") ProductDtos.SortOption sort,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        // Validate size
        if (size < 1 || size > 100) {
            size = 20;
        }
        if (page < 0) {
            page = 0;
        }

        Page<ProductDtos.PublicListItem> result = productPublicService.list(
            categoryId, brandId, sort, page, size
        );

        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ApiResponse.success(result.getContent(), meta);
    }

    /**
     * 상품 상세 조회.
     *
     * @param id 상품 ID
     * @return 상품 상세 (옵션, 이미지, 카테고리 포함)
     * @throws com.olive.commerce.common.error.BusinessException 상품 없음 또는 비공개 상품
     */
    @GetMapping("/{id}")
    public ApiResponse<ProductDtos.PublicDetailResponse> getDetail(@PathVariable Long id) {
        ProductDtos.PublicDetailResponse detail = productPublicService.getDetail(id);
        return ApiResponse.success(detail);
    }
}
