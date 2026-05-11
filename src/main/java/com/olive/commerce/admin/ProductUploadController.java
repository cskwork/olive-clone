package com.olive.commerce.admin;

import com.olive.commerce.common.api.ApiResponse;
import com.olive.commerce.product.ProductDtos;
import com.olive.commerce.product.ProductUploadService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 이미지 업로드 컨트롤러.
 * POST /api/admin/uploads/product-image — S3 presigned URL 발급
 */
@RestController
@RequestMapping("/api/admin/uploads")
public class ProductUploadController {

    private final ProductUploadService productUploadService;

    public ProductUploadController(ProductUploadService productUploadService) {
        this.productUploadService = productUploadService;
    }

    @PostMapping("/product-image")
    @PreAuthorize("hasRole('PRODUCT_ADMIN')")
    public ResponseEntity<ApiResponse<ProductDtos.PresignedUrlResponse>> getPresignedUrl(
        @Valid @RequestBody ProductDtos.PresignedUrlRequest request
    ) {
        ProductDtos.PresignedUrlResponse response = productUploadService.getPresignedUrl(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }
}
