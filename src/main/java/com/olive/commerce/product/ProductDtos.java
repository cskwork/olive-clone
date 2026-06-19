package com.olive.commerce.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 상품 관련 DTO들 (PRD §6.2, §7.2).
 */
public sealed interface ProductDtos {

    // Presigned URL Request/Response
    record PresignedUrlRequest(
        @NotBlank(message = "파일명은 필수입니다")
        String filename,

        @NotNull(message = "파일 크기는 필수입니다")
        @DecimalMin(value = "1", message = "파일 크기는 1바이트 이상이어야 합니다")
        Long fileSize,

        @NotBlank(message = "Content-Type은 필수입니다")
        String contentType
    ) implements ProductDtos {}

    record PresignedUrlResponse(
        String uploadUrl,
        String fileUrl
    ) implements ProductDtos {}

    // Product Option DTOs
    record OptionCreateRequest(
        @NotBlank(message = "옵션명은 필수입니다")
        @Size(max = 100, message = "옵션명은 100자 이하여야 합니다")
        String optionName,

        @NotNull(message = "옵션 가격은 필수입니다")
        @DecimalMin(value = "0", message = "옵션 가격은 0 이상이어야 합니다")
        BigDecimal optionPrice
    ) implements ProductDtos {}

    record OptionUpdateRequest(
        @NotBlank(message = "옵션명은 필수입니다")
        @Size(max = 100, message = "옵션명은 100자 이하여야 합니다")
        String optionName,

        @NotNull(message = "옵션 가격은 필수입니다")
        @DecimalMin(value = "0", message = "옵션 가격은 0 이상이어야 합니다")
        BigDecimal optionPrice,

        ProductOption.OptionStatus status
    ) implements ProductDtos {}

    record OptionResponse(
        Long id,
        String optionName,
        BigDecimal optionPrice,
        ProductOption.OptionStatus status
    ) implements ProductDtos {
        static OptionResponse from(ProductOption option) {
            return new OptionResponse(
                option.getId(),
                option.getOptionName(),
                option.getOptionPrice(),
                option.getStatus()
            );
        }
    }

    // Product Image DTOs
    record ImageResponse(
        Long id,
        String url,
        Integer sortOrder,
        Boolean isThumbnail
    ) implements ProductDtos {
        static ImageResponse from(ProductImage image) {
            return new ImageResponse(
                image.getId(),
                image.getUrl(),
                image.getSortOrder(),
                image.getSortOrder() == 1  // sortOrder가 1이면 썸네일
            );
        }
    }

    // Product Create Request
    record AdminCreateRequest(
        @NotNull(message = "브랜드 ID는 필수입니다")
        Long brandId,

        @NotBlank(message = "상품명은 필수입니다")
        @Size(max = 255, message = "상품명은 255자 이하여야 합니다")
        String name,

        String description,

        @NotNull(message = "기본 가격은 필수입니다")
        @DecimalMin(value = "0", message = "기본 가격은 0 이상이어야 합니다")
        BigDecimal basePrice,

        @DecimalMin(value = "0", message = "할인 가격은 0 이상이어야 합니다")
        BigDecimal salePrice,

        Product.ProductStatus status,

        @NotEmpty(message = "카테고리는 최소 1개 이상이어야 합니다")
        List<@NotNull Long> categoryIds,

        @NotEmpty(message = "옵션은 최소 1개 이상이어야 합니다")
        List<@Valid OptionCreateRequest> options,

        @NotEmpty(message = "이미지는 최소 1개 이상이어야 합니다")
        List<@NotBlank String> imageUrls
    ) implements ProductDtos {}

    // Product Update Request (partial update)
    record AdminUpdateRequest(
        @Size(max = 255, message = "상품명은 255자 이하여야 합니다")
        String name,

        String description,

        @DecimalMin(value = "0", message = "기본 가격은 0 이상이어야 합니다")
        BigDecimal basePrice,

        @DecimalMin(value = "0", message = "할인 가격은 0 이상이어야 합니다")
        BigDecimal salePrice,

        Product.ProductStatus status
    ) implements ProductDtos {}

    // Product Response
    record AdminResponse(
        Long id,
        Long brandId,
        String brandName,
        String name,
        String description,
        BigDecimal basePrice,
        BigDecimal salePrice,
        Product.ProductStatus status,
        List<CategoryResponse> categories,
        List<OptionResponse> options,
        List<ImageResponse> images,
        String createdAt,
        String updatedAt
    ) implements ProductDtos {
        static AdminResponse from(Product product, List<CategoryResponse> categories) {
            return new AdminResponse(
                product.getId(),
                product.getBrandId(),
                product.getBrand() != null ? product.getBrand().getName() : null,
                product.getName(),
                product.getDescription(),
                product.getBasePrice(),
                product.getSalePrice(),
                product.getStatus(),
                categories,
                product.getOptions().stream().map(OptionResponse::from).toList(),
                product.getImages().stream().map(ImageResponse::from).toList(),
                product.getCreatedAt().toString(),
                product.getUpdatedAt().toString()
            );
        }
    }

    // Category summary for Product response
    record CategoryResponse(
        Long id,
        String name,
        String slug
    ) implements ProductDtos {
        static CategoryResponse from(Category category) {
            return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug()
            );
        }
    }

    // ========== Public API DTOs (OLV-023) ==========

    // Public list item (PRD §8.1 shape)
    record PublicListItem(
        Long productId,
        String brandName,
        String productName,
        BigDecimal salePrice,
        BigDecimal originalPrice,
        BigDecimal discountRate,
        String thumbnailUrl,
        BigDecimal rating,
        Integer reviewCount
    ) implements ProductDtos {
        static PublicListItem from(Product product, String thumbnailUrl) {
            BigDecimal salePrice = product.getSalePrice() != null
                ? product.getSalePrice()
                : product.getBasePrice();
            BigDecimal originalPrice = product.getBasePrice();
            BigDecimal discountRate = calculateDiscountRate(originalPrice, salePrice);

            return new PublicListItem(
                product.getId(),
                product.getBrand() != null ? product.getBrand().getName() : null,
                product.getName(),
                salePrice,
                originalPrice,
                discountRate,
                thumbnailUrl,
                BigDecimal.ZERO,  // rating: review domain未実装
                0                 // reviewCount: review domain未実装
            );
        }

        private static BigDecimal calculateDiscountRate(BigDecimal original, BigDecimal sale) {
            if (original.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }
            return original.subtract(sale)
                .divide(original, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, java.math.RoundingMode.HALF_UP);
        }
    }

    // Public detail response
    record PublicDetailResponse(
        Long productId,
        String brandName,
        String brandLogoUrl,
        String productName,
        String description,
        BigDecimal salePrice,
        BigDecimal originalPrice,
        BigDecimal discountRate,
        List<OptionSummary> options,
        List<ImageDetail> images,
        List<CategoryPath> categories,
        BigDecimal rating,
        Integer reviewCount
    ) implements ProductDtos {
        record OptionSummary(
            Long optionId,
            String optionName,
            BigDecimal optionPrice,
            ProductOption.OptionStatus status,
            Long availableQuantity  // NULL if inventory未実装 (OLV-031)
        ) {}

        record ImageDetail(
            Long imageId,
            String url,
            Integer sortOrder,
            Boolean isThumbnail
        ) {}

        record CategoryPath(
            Long categoryId,
            String categoryName,
            String categorySlug
        ) {}
    }

    // Sort options for public list
    public enum SortOption {
        POPULAR, LATEST, PRICE_ASC, PRICE_DESC, RATING
    }

    /**
     * 랭킹 목록 응답 항목 (rank_score 기준 정렬).
     */
    record RankingItem(
        Long productId,
        String brandName,
        String productName,
        BigDecimal salePrice,
        BigDecimal originalPrice,
        BigDecimal discountRate,
        String thumbnailUrl,
        BigDecimal rating,
        Integer reviewCount,
        Long salesCount,
        BigDecimal rankScore
    ) implements ProductDtos {}
}
