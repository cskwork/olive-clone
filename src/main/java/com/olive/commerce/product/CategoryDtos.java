package com.olive.commerce.product;

import com.olive.commerce.product.CategoryRepository.CategoryProjection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public sealed interface CategoryDtos {

    record AdminCreateRequest(
        @NotBlank(message = "카테고리명은 필수입니다")
        @Size(max = 100, message = "카테고리명은 100자 이하여야 합니다")
        String name,

        @NotBlank(message = "슬러그는 필수입니다")
        @Size(max = 100, message = "슬러그는 100자 이하여야 합니다")
        String slug,

        Long parentId,

        @NotNull(message = "정렬 순서는 필수입니다")
        Integer sortOrder
    ) implements CategoryDtos {}

    record AdminUpdateRequest(
        @NotBlank(message = "카테고리명은 필수입니다")
        @Size(max = 100, message = "카테고리명은 100자 이하여야 합니다")
        String name,

        Long parentId,

        @NotNull(message = "정렬 순서는 필수입니다")
        Integer sortOrder
    ) implements CategoryDtos {}

    record AdminResponse(
        Long id,
        String name,
        String slug,
        Long parentId,
        Integer sortOrder,
        Integer depth,
        String createdAt,
        String updatedAt
    ) implements CategoryDtos {
        static AdminResponse from(Category category) {
            return new AdminResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getParentId(),
                category.getSortOrder(),
                category.getDepth(),
                category.getCreatedAt().toString(),
                category.getUpdatedAt().toString()
            );
        }
    }

    record PublicTreeNode(
        Long id,
        String name,
        String slug,
        List<PublicTreeNode> children
    ) implements CategoryDtos {
        static PublicTreeNode from(Category category) {
            return new PublicTreeNode(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getChildren().stream()
                    .map(PublicTreeNode::from)
                    .toList()
            );
        }
    }

    record PublicTreeResponse(
        List<PublicTreeNode> categories
    ) implements CategoryDtos {}
}
