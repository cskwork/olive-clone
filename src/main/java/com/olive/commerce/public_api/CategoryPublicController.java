package com.olive.commerce.public_api;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.product.CategoryDtos;
import com.olive.commerce.product.CategoryPublicService;
import com.olive.commerce.product.CategoryRepository;
import com.olive.commerce.product.ProductDtos;
import com.olive.commerce.product.ProductPublicService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 공개 카테고리 API.
 *
 * GET /api/categories                  - 카테고리 트리
 * GET /api/categories/{id}/products    - 카테고리 내 상품 목록 (OLV-C3)
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryPublicController {

    private final CategoryPublicService categoryPublicService;
    private final CategoryRepository categoryRepository;
    private final ProductPublicService productPublicService;

    public CategoryPublicController(
            CategoryPublicService categoryPublicService,
            CategoryRepository categoryRepository,
            ProductPublicService productPublicService) {
        this.categoryPublicService = categoryPublicService;
        this.categoryRepository = categoryRepository;
        this.productPublicService = productPublicService;
    }

    @GetMapping
    public ApiResponse<CategoryDtos.PublicTreeResponse> getTree() {
        return ApiResponse.success(categoryPublicService.getTree());
    }

    /**
     * 특정 카테고리에 속한 상품 목록을 반환합니다.
     * 카테고리가 존재하지 않으면 404 CATEGORY_NOT_FOUND.
     */
    @GetMapping("/{id}/products")
    public ApiResponse<List<ProductDtos.PublicListItem>> listProducts(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "LATEST") ProductDtos.SortOption sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!categoryRepository.existsById(id)) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "categoryId=" + id);
        }
        if (size < 1 || size > 100) size = 20;
        if (page < 0) page = 0;

        Page<ProductDtos.PublicListItem> result = productPublicService.list(id, null, sort, page, size);
        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ApiResponse.success(result.getContent(), meta);
    }
}
