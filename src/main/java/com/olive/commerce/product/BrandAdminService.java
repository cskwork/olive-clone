package com.olive.commerce.product;

import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.product.BrandDtos.AdminCreateRequest;
import com.olive.commerce.product.BrandDtos.AdminResponse;
import com.olive.commerce.product.BrandDtos.AdminUpdateRequest;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrandAdminService {

    private final BrandRepository brandRepository;
    private final EntityManager em;

    public BrandAdminService(BrandRepository brandRepository, EntityManager em) {
        this.brandRepository = brandRepository;
        this.em = em;
    }

    @Transactional
    public AdminResponse create(AdminCreateRequest request) {
        if (brandRepository.existsBySlug(request.slug())) {
            throw new BusinessException(ErrorCode.BRAND_SLUG_DUPLICATE,
                "이미 존재하는 슬러그입니다: " + request.slug());
        }
        Brand brand = Brand.create(request.name(), request.slug(), request.logoUrl());
        Brand saved = brandRepository.save(brand);
        em.flush(); // Ensure DB triggers populate createdAt/updatedAt
        em.refresh(saved); // Re-read to get trigger-generated values
        return AdminResponse.from(saved);
    }

    public AdminResponse get(Long id) {
        Brand brand = brandRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.BRAND_NOT_FOUND,
                "브랜드를 찾을 수 없습니다: " + id));
        return AdminResponse.from(brand);
    }

    public Page<AdminResponse> list(String name, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id"));
        Page<Brand> brands = brandRepository.findAllActive(name, pageable);
        return brands.map(AdminResponse::from);
    }

    @Transactional
    public AdminResponse update(Long id, AdminUpdateRequest request) {
        Brand brand = brandRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.BRAND_NOT_FOUND,
                "브랜드를 찾을 수 없습니다: " + id));
        brand.update(request.name(), request.logoUrl(), request.status());
        Brand updated = brandRepository.save(brand);
        return AdminResponse.from(updated);
    }
}
