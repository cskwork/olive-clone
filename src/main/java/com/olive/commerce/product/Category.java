package com.olive.commerce.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 카테고리 엔티티 (PRD §7.3).
 * parent_id는 self-referencing FK로 계층 구조 표현.
 */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "name", nullable = false, length = 100)
    @NotBlank(message = "카테고리명은 필수입니다")
    @Size(max = 100, message = "카테고리명은 100자 이하여야 합니다")
    private String name;

    @Column(name = "slug", nullable = false, length = 100)
    @NotBlank(message = "슬러그는 필수입니다")
    @Size(max = 100, message = "슬러그는 100자 이하여야 합니다")
    private String slug;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "depth", nullable = false)
    private Integer depth = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Transient
    private List<Category> children = new ArrayList<>();

    protected Category() {}

    private Category(String name, String slug, Long parentId, Integer sortOrder, Integer depth) {
        this.name = name;
        this.slug = slug;
        this.parentId = parentId;
        this.sortOrder = sortOrder;
        this.depth = depth;
    }

    public static Category createTopLevel(String name, String slug, Integer sortOrder) {
        return new Category(name, slug, null, sortOrder, 0);
    }

    public static Category createChild(String name, String slug, Long parentId, Integer sortOrder, Integer depth) {
        return new Category(name, slug, parentId, sortOrder, depth);
    }

    public void update(String name, Long parentId, Integer sortOrder) {
        this.name = name;
        this.parentId = parentId;
        this.sortOrder = sortOrder;
    }

    public void addChild(Category child) {
        this.children.add(child);
    }

    public Long getId() {
        return id;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public Integer getDepth() {
        return depth;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public List<Category> getChildren() {
        return children;
    }
}
