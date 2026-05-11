package com.olive.commerce.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * 브랜드 엔티티 (PRD §7.2).
 * name과 slug는 UNIQUE 제약으로 중복 방지.
 */
@Entity
@Table(name = "brands", uniqueConstraints = {
    @UniqueConstraint(columnNames = "name"),
    @UniqueConstraint(columnNames = "slug")
})
public class Brand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    @NotBlank(message = "브랜드명은 필수입니다")
    @Size(max = 100, message = "브랜드명은 100자 이하여야 합니다")
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 100)
    @NotBlank(message = "슬러그는 필수입니다")
    @Size(max = 100, message = "슬러그는 100자 이하여야 합니다")
    private String slug;

    @Column(name = "logo_url", length = 512)
    private String logoUrl;

    @Column(name = "status", nullable = false, length = 20)
    private String status = BrandStatus.ACTIVE.name();

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected Brand() {}

    private Brand(String name, String slug, String logoUrl) {
        this.name = name;
        this.slug = slug;
        this.logoUrl = logoUrl;
        this.status = BrandStatus.ACTIVE.name();
    }

    public static Brand create(String name, String slug, String logoUrl) {
        return new Brand(name, slug, logoUrl);
    }

    public void update(String name, String logoUrl, BrandStatus status) {
        this.name = name;
        this.logoUrl = logoUrl;
        this.status = status.name();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public BrandStatus getStatus() {
        return BrandStatus.valueOf(status);
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public enum BrandStatus {
        ACTIVE, INACTIVE
    }
}
