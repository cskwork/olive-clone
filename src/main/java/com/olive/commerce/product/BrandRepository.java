package com.olive.commerce.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BrandRepository extends JpaRepository<Brand, Long> {

    Optional<Brand> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Query("SELECT b FROM Brand b WHERE b.status = 'ACTIVE' AND (:name IS NULL OR b.name LIKE %:name%)")
    Page<Brand> findAllActive(@Param("name") String name, Pageable pageable);
}
