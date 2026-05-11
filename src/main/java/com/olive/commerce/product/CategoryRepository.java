package com.olive.commerce.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    @Query("""
        SELECT c FROM Category c
        WHERE c.parentId IS NULL
        ORDER BY c.sortOrder
        """)
    List<Category> findTopLevelCategories();

    @Query(value = "WITH RECURSIVE category_tree AS ((SELECT id, name, slug, parent_id, sort_order, depth FROM categories WHERE parent_id IS NULL ORDER BY sort_order) UNION ALL SELECT c.id, c.name, c.slug, c.parent_id, c.sort_order, c.depth FROM categories c INNER JOIN category_tree ct ON c.parent_id = ct.id) SELECT * FROM category_tree ORDER BY sort_order", nativeQuery = true)
    List<CategoryProjection> findTreeAsProjections();

    @Query(value = """
        SELECT COUNT(*) > 0 FROM product_category_mapping
        WHERE category_id = :categoryId
        """, nativeQuery = true)
    boolean hasProducts(@Param("categoryId") Long categoryId);

    interface CategoryProjection {
        Long getId();
        String getName();
        String getSlug();
        Long getParentId();
        Integer getSortOrder();
        Integer getDepth();
    }
}
