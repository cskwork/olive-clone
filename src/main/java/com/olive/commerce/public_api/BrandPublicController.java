package com.olive.commerce.public_api;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.product.BrandDtos;
import com.olive.commerce.product.BrandRepository;
import com.olive.commerce.product.ProductDtos;
import com.olive.commerce.product.ProductPublicService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 공개 브랜드 API.
 *
 * GET /api/brands                  - 브랜드 목록
 * GET /api/brands/{id}/products    - 브랜드 내 상품 목록 (OLV-C3)
 */
@RestController
@RequestMapping("/api/brands")
public class BrandPublicController {

    private final BrandRepository brandRepository;
    private final ProductPublicService productPublicService;

    public BrandPublicController(
            BrandRepository brandRepository,
            ProductPublicService productPublicService) {
        this.brandRepository = brandRepository;
        this.productPublicService = productPublicService;
    }

    @GetMapping
    public ApiResponse<List<BrandDtos.PublicResponse>> list(
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id"));
        Page<BrandDtos.PublicResponse> result = brandRepository.findAllActive(name, pageable)
            .map(BrandDtos::toPublicResponse);
        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ApiResponse.success(result.getContent(), meta);
    }

    /**
     * 특정 브랜드에 속한 상품 목록을 반환합니다.
     * 브랜드가 존재하지 않으면 404 BRAND_NOT_FOUND.
     */
    @GetMapping("/{id}/products")
    public ApiResponse<List<ProductDtos.PublicListItem>> listProducts(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "LATEST") ProductDtos.SortOption sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!brandRepository.existsById(id)) {
            throw new BusinessException(ErrorCode.BRAND_NOT_FOUND, "brandId=" + id);
        }
        if (size < 1 || size > 100) size = 20;
        if (page < 0) page = 0;

        Page<ProductDtos.PublicListItem> result = productPublicService.list(null, id, sort, page, size);
        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ApiResponse.success(result.getContent(), meta);
    }
}
