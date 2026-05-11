package com.olive.commerce.admin;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.product.CategoryAdminService;
import com.olive.commerce.product.CategoryDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/categories")
public class CategoryAdminController {

    private final CategoryAdminService categoryAdminService;

    public CategoryAdminController(CategoryAdminService categoryAdminService) {
        this.categoryAdminService = categoryAdminService;
    }

    @PostMapping
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ResponseEntity<ApiResponse<CategoryDtos.AdminResponse>> create(@Valid @RequestBody CategoryDtos.AdminCreateRequest request) {
        CategoryDtos.AdminResponse response = categoryAdminService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<CategoryDtos.AdminResponse> get(@PathVariable Long id) {
        return ApiResponse.success(categoryAdminService.get(id));
    }

    @GetMapping
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<List<CategoryDtos.AdminResponse>> listAsTree() {
        return ApiResponse.success(categoryAdminService.listAsTree());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<CategoryDtos.AdminResponse> update(
        @PathVariable Long id,
        @Valid @RequestBody CategoryDtos.AdminUpdateRequest request
    ) {
        return ApiResponse.success(categoryAdminService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        categoryAdminService.delete(id);
        return ApiResponse.success(null);
    }
}
