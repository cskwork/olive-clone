package com.olive.commerce.product;

import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.product.CategoryDtos.AdminCreateRequest;
import com.olive.commerce.product.CategoryDtos.AdminResponse;
import com.olive.commerce.product.CategoryDtos.AdminUpdateRequest;
import jakarta.persistence.EntityManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class CategoryAdminService {

    private static final String CACHE_KEY = "cache:categories:tree";

    private final CategoryRepository categoryRepository;
    private final StringRedisTemplate redisTemplate;
    private final EntityManager em;

    public CategoryAdminService(CategoryRepository categoryRepository,
                               StringRedisTemplate redisTemplate,
                               EntityManager em) {
        this.categoryRepository = categoryRepository;
        this.redisTemplate = redisTemplate;
        this.em = em;
    }

    @Transactional
    public AdminResponse create(AdminCreateRequest request) {
        Integer depth = calculateDepth(request.parentId());
        Category category = request.parentId() == null
            ? Category.createTopLevel(request.name(), request.slug(), request.sortOrder())
            : Category.createChild(request.name(), request.slug(), request.parentId(), request.sortOrder(), depth);
        Category saved = categoryRepository.save(category);
        em.flush(); // Ensure DB triggers populate createdAt/updatedAt
        em.refresh(saved); // Re-read to get trigger-generated values
        invalidateCache();
        return AdminResponse.from(saved);
    }

    public AdminResponse get(Long id) {
        Category category = categoryRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND,
                "카테고리를 찾을 수 없습니다: " + id));
        return AdminResponse.from(category);
    }

    public List<AdminResponse> listAsTree() {
        List<Category> categories = categoryRepository.findTopLevelCategories();
        return categories.stream()
            .map(this::buildTree)
            .toList();
    }

    @Transactional
    public AdminResponse update(Long id, AdminUpdateRequest request) {
        Category category = categoryRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND,
                "카테고리를 찾을 수 없습니다: " + id));

        // 순환 참조 방지
        if (request.parentId() != null && request.parentId().equals(id)) {
            throw new BusinessException(ErrorCode.CATEGORY_CYCLE_DETECTED,
                "자기 자신을 부모로 설정할 수 없습니다");
        }

        Integer newDepth = calculateDepth(request.parentId());
        category.update(request.name(), request.parentId(), request.sortOrder());
        categoryRepository.save(category);
        invalidateCache();
        return AdminResponse.from(category);
    }

    @Transactional
    public void delete(Long id) {
        if (!categoryRepository.existsById(id)) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND,
                "카테고리를 찾을 수 없습니다: " + id);
        }

        long productCount = getProductCount(id);
        if (productCount > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_PRODUCTS,
                "카테고리에 매핑된 상품이 " + productCount + "개 있어 삭제할 수 없습니다");
        }

        categoryRepository.deleteById(id);
        invalidateCache();
    }

    private void invalidateCache() {
        redisTemplate.delete(CACHE_KEY);
    }

    private Integer calculateDepth(Long parentId) {
        if (parentId == null) {
            return 0;
        }
        Category parent = categoryRepository.findById(parentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND,
                "부모 카테고리를 찾을 수 없습니다: " + parentId));
        return parent.getDepth() + 1;
    }

    private AdminResponse buildTree(Category category) {
        AdminResponse response = AdminResponse.from(category);
        // children would be populated recursively if needed for admin tree view
        return response;
    }

    private long getProductCount(Long categoryId) {
        return categoryRepository.hasProducts(categoryId) ? 1 : 0;
    }
}
