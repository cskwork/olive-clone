package com.olive.commerce.admin;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.product.ProductAdminService;
import com.olive.commerce.product.ProductDtos;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 상품 관리자 컨트롤러.
 * POST /api/admin/products — 상품 생성
 * GET /api/admin/products/{id} — 상품 조회
 * GET /api/admin/products — 상품 목록 (페이지네이션 + 필터)
 * PATCH /api/admin/products/{id} — 상품 수정
 * POST /api/admin/products/{id}/options — 옵션 추가
 * PATCH /api/admin/products/{id}/options/{optionId} — 옵션 수정
 */
@RestController
@RequestMapping("/api/admin/products")
public class ProductAdminController {

    private final ProductAdminService productAdminService;

    public ProductAdminController(ProductAdminService productAdminService) {
        this.productAdminService = productAdminService;
    }

    @PostMapping
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ResponseEntity<ApiResponse<ProductDtos.AdminResponse>> create(@Valid @RequestBody ProductDtos.AdminCreateRequest request) {
        ProductDtos.AdminResponse response = productAdminService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<ProductDtos.AdminResponse> get(@PathVariable Long id) {
        return ApiResponse.success(productAdminService.get(id));
    }

    @GetMapping
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<List<ProductDtos.AdminResponse>> list(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Long brandId,
        @RequestParam(required = false) Long categoryId,
        @RequestParam(required = false) String name,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Page<ProductDtos.AdminResponse> result = productAdminService.list(status, brandId, categoryId, name, page, size);
        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ApiResponse.success(result.getContent(), meta);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<ProductDtos.AdminResponse> update(
        @PathVariable Long id,
        @Valid @RequestBody ProductDtos.AdminUpdateRequest request
    ) {
        return ApiResponse.success(productAdminService.update(id, request));
    }

    @PostMapping("/{id}/options")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ResponseEntity<ApiResponse<ProductDtos.OptionResponse>> addOption(
        @PathVariable Long id,
        @Valid @RequestBody ProductDtos.OptionCreateRequest request
    ) {
        ProductDtos.OptionResponse response = productAdminService.addOption(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PatchMapping("/{id}/options/{optionId}")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<ProductDtos.OptionResponse> updateOption(
        @PathVariable Long id,
        @PathVariable Long optionId,
        @Valid @RequestBody ProductDtos.OptionUpdateRequest request
    ) {
        return ApiResponse.success(productAdminService.updateOption(id, optionId, request));
    }
}
