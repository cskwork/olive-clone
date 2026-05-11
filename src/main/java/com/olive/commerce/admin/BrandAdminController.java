package com.olive.commerce.admin;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.common.api.PageMeta;
import com.olive.commerce.product.BrandAdminService;
import com.olive.commerce.product.BrandDtos;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/brands")
public class BrandAdminController {

    private final BrandAdminService brandAdminService;

    public BrandAdminController(BrandAdminService brandAdminService) {
        this.brandAdminService = brandAdminService;
    }

    @PostMapping
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ResponseEntity<ApiResponse<BrandDtos.AdminResponse>> create(@Valid @RequestBody BrandDtos.AdminCreateRequest request) {
        BrandDtos.AdminResponse response = brandAdminService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<BrandDtos.AdminResponse> get(@PathVariable Long id) {
        return ApiResponse.success(brandAdminService.get(id));
    }

    @GetMapping
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<List<BrandDtos.AdminResponse>> list(
        @RequestParam(required = false) String name,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Page<BrandDtos.AdminResponse> result = brandAdminService.list(name, page, size);
        PageMeta meta = new PageMeta(page, size, result.getTotalElements());
        return ApiResponse.success(result.getContent(), meta);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<BrandDtos.AdminResponse> update(
        @PathVariable Long id,
        @Valid @RequestBody BrandDtos.AdminUpdateRequest request
    ) {
        return ApiResponse.success(brandAdminService.update(id, request));
    }
}
