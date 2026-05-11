package com.olive.commerce.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public sealed interface BrandDtos {

    record AdminCreateRequest(
        @NotBlank(message = "브랜드명은 필수입니다")
        @Size(max = 100, message = "브랜드명은 100자 이하여야 합니다")
        String name,

        @NotBlank(message = "슬러그는 필수입니다")
        @Size(max = 100, message = "슬러그는 100자 이하여야 합니다")
        String slug,

        String logoUrl
    ) implements BrandDtos {}

    record AdminUpdateRequest(
        @NotBlank(message = "브랜드명은 필수입니다")
        @Size(max = 100, message = "브랜드명은 100자 이하여야 합니다")
        String name,

        String logoUrl,

        Brand.BrandStatus status
    ) implements BrandDtos {}

    record AdminResponse(
        Long id,
        String name,
        String slug,
        String logoUrl,
        Brand.BrandStatus status,
        String createdAt,
        String updatedAt
    ) implements BrandDtos {
        static AdminResponse from(Brand brand) {
            return new AdminResponse(
                brand.getId(),
                brand.getName(),
                brand.getSlug(),
                brand.getLogoUrl(),
                brand.getStatus(),
                brand.getCreatedAt().toString(),
                brand.getUpdatedAt().toString()
            );
        }
    }

    record PublicResponse(
        Long id,
        String name,
        String slug,
        String logoUrl
    ) implements BrandDtos {
        static PublicResponse from(Brand brand) {
            return new PublicResponse(
                brand.getId(),
                brand.getName(),
                brand.getSlug(),
                brand.getLogoUrl()
            );
        }
    }

    // Public factory method for external package access
    static PublicResponse toPublicResponse(Brand brand) {
        return PublicResponse.from(brand);
    }
}
