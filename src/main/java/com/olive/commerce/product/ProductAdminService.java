package com.olive.commerce.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.common.audit.AuditLogger;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 상품 관리자 서비스.
 * 상품 CRUD, 옵션 관리, 카테고리 매핑을 담당한다.
 */
@Service
public class ProductAdminService {

    private static final Logger log = LoggerFactory.getLogger(ProductAdminService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductCategoryMappingRepository categoryMappingRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    private final EntityManager em;
    private final AuditLogger auditLogger;
    private final ApplicationEventPublisher eventPublisher;

    public ProductAdminService(
        ProductRepository productRepository,
        ProductOptionRepository productOptionRepository,
        ProductImageRepository productImageRepository,
        ProductCategoryMappingRepository categoryMappingRepository,
        BrandRepository brandRepository,
        CategoryRepository categoryRepository,
        EntityManager em,
        AuditLogger auditLogger,
        ApplicationEventPublisher eventPublisher
    ) {
        this.productRepository = productRepository;
        this.productOptionRepository = productOptionRepository;
        this.productImageRepository = productImageRepository;
        this.categoryMappingRepository = categoryMappingRepository;
        this.brandRepository = brandRepository;
        this.categoryRepository = categoryRepository;
        this.em = em;
        this.auditLogger = auditLogger;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 상품을 생성한다.
     * 옵션, 카테고리, 이미지를 함께 생성한다.
     */
    @Transactional
    public ProductDtos.AdminResponse create(ProductDtos.AdminCreateRequest request) {
        // 브랜드 로드
        Brand brand = brandRepository.findById(request.brandId())
            .orElseThrow(() -> new BusinessException(ErrorCode.BRAND_NOT_FOUND,
                "브랜드를 찾을 수 없습니다: " + request.brandId()));

        // 카테고리 존재 확인
        for (Long categoryId : request.categoryIds()) {
            if (!categoryRepository.existsById(categoryId)) {
                throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND,
                    "카테고리를 찾을 수 없습니다: " + categoryId);
            }
        }

        // 상품 생성 (요청한 상태로 직접 생성)
        Product product = Product.createWithStatus(
            request.brandId(),
            request.name(),
            request.description(),
            request.basePrice(),
            request.salePrice(),
            request.status()
        );

        // Brand 관계 설정
        product.setBrand(brand);

        // 옵션 추가
        for (ProductDtos.OptionCreateRequest optionReq : request.options()) {
            ProductOption option = ProductOption.create(optionReq.optionName(), optionReq.optionPrice());
            product.addOption(option);
        }

        // 이미지 추가
        int sortOrder = 1;
        for (String imageUrl : request.imageUrls()) {
            ProductImage image = ProductImage.create(imageUrl, sortOrder++, sortOrder == 1);
            product.addImage(image);
        }

        Product saved = productRepository.save(product);

        // 카테고리 매핑
        for (Long categoryId : request.categoryIds()) {
            ProductCategoryMapping mapping = ProductCategoryMapping.create(saved.getId(), categoryId);
            categoryMappingRepository.save(mapping);
        }

        em.flush();
        em.refresh(saved);

        // 감사 로그
        logAudit("CREATE", null, buildAuditSnapshot(saved));

        // OLV-023: 캐시 무효화 이벤트 발행
        eventPublisher.publishEvent(new ProductUpdatedEvent(saved.getId()));

        return buildAdminResponse(saved);
    }

    /**
     * 상품을 조회한다. 옵션, 이미지, 카테고리를 포함한다.
     */
    @Transactional(readOnly = true)
    public ProductDtos.AdminResponse get(Long id) {
        Product product = productRepository.findByIdWithDetails(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                "상품을 찾을 수 없습니다: " + id));
        return buildAdminResponse(product);
    }

    /**
     * 상품 목록을 조회한다. 페이지네이션과 필터링을 지원한다.
     */
    @Transactional(readOnly = true)
    public Page<ProductDtos.AdminResponse> list(String status, Long brandId, Long categoryId, String name, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());

        Page<Product> products;
        if (categoryId != null) {
            // 카테고리 필터: 해당 카테고리에 속한 상품 ID 목록 조회
            List<?> rawProductIds = em.createNativeQuery(
                    "SELECT product_id FROM product_category_mapping WHERE category_id = :categoryId"
                )
                .setParameter("categoryId", categoryId)
                .getResultList();
            List<Long> productIds = rawProductIds.stream()
                .map(id -> ((Number) id).longValue())
                .toList();
            // ID 목록으로 조회 후 Page 적용
            List<Product> productList = productRepository.findAllById(productIds);
            products = createPageFromList(productList, pageable, productRepository.count());
        } else {
            products = productRepository.findAllWithFilters(status, brandId, name, pageable);
        }

        return products.map(p -> buildAdminResponse(p));
    }

    // Helper method to create a Page from a List
    private Page<Product> createPageFromList(List<Product> list, Pageable pageable, long totalElements) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), list.size());
        List<Product> sublist = start < list.size() ? list.subList(start, end) : List.of();
        return new org.springframework.data.domain.PageImpl<>(sublist, pageable, totalElements);
    }

    /**
     * 상품을 수정한다. 부분 업데이트를 지원한다.
     */
    @Transactional
    public ProductDtos.AdminResponse update(Long id, ProductDtos.AdminUpdateRequest request) {
        Product product = productRepository.findByIdWithDetails(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                "상품을 찾을 수 없습니다: " + id));

        String beforeSnapshot = buildAuditSnapshot(product);

        try {
            // 상태 천이 검증은 Product.update()에서 처리
            product.update(
                request.name() != null ? request.name() : product.getName(),
                request.description() != null ? request.description() : product.getDescription(),
                request.basePrice() != null ? request.basePrice() : product.getBasePrice(),
                request.salePrice(), // null 허용 (할인 해제)
                request.status()
            );
        } catch (IllegalArgumentException e) {
            // 상태 천이 실패 시 BusinessException으로 변환
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_STATE_TRANSITION, e.getMessage());
        }

        Product updated = productRepository.save(product);

        // 감사 로그
        logAudit("UPDATE", beforeSnapshot, buildAuditSnapshot(updated));

        // OLV-023: 캐시 무효화 이벤트 발행
        eventPublisher.publishEvent(new ProductUpdatedEvent(updated.getId()));

        return buildAdminResponse(updated);
    }

    /**
     * 상품에 옵션을 추가한다.
     */
    @Transactional
    public ProductDtos.OptionResponse addOption(Long productId, ProductDtos.OptionCreateRequest request) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                "상품을 찾을 수 없습니다: " + productId));

        ProductOption option = ProductOption.create(request.optionName(), request.optionPrice());
        product.addOption(option);

        ProductOption saved = productOptionRepository.save(option);

        Map<String, Object> optionData = new HashMap<>();
        optionData.put("productId", productId);
        optionData.put("optionId", saved.getId());
        optionData.put("optionName", saved.getOptionName());
        optionData.put("optionPrice", saved.getOptionPrice());
        logAudit("ADD_OPTION", null, toJson(optionData));

        // OLV-023: 캐시 무효화 이벤트 발행
        eventPublisher.publishEvent(new ProductUpdatedEvent(productId));

        return ProductDtos.OptionResponse.from(saved);
    }

    /**
     * 옵션을 수정한다.
     */
    @Transactional
    public ProductDtos.OptionResponse updateOption(Long productId, Long optionId, ProductDtos.OptionUpdateRequest request) {
        ProductOption option = productOptionRepository.findById(optionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                "옵션을 찾을 수 없습니다: " + optionId));

        if (!option.getProduct().getId().equals(productId)) {
            throw new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND,
                "옵션이 해당 상품에 속하지 않습니다: productId=" + productId + ", optionId=" + optionId);
        }

        String beforeSnapshot = buildOptionSnapshot(option);

        option.update(request.optionName(), request.optionPrice(), request.status());
        ProductOption updated = productOptionRepository.save(option);

        logAudit("UPDATE_OPTION", beforeSnapshot, buildOptionSnapshot(updated));

        // OLV-023: 캐시 무효화 이벤트 발행
        eventPublisher.publishEvent(new ProductUpdatedEvent(productId));

        return ProductDtos.OptionResponse.from(updated);
    }

    private ProductDtos.AdminResponse buildAdminResponse(Product product) {
        // 카테고리 직접 로드 (매핑 테이블에서 ID를 가져온 후 Category 엔티티 조회)
        List<?> rawCategoryIds = em.createNativeQuery(
                "SELECT category_id FROM product_category_mapping WHERE product_id = :productId"
            )
            .setParameter("productId", product.getId())
            .getResultList();

        List<Long> categoryIds = rawCategoryIds.stream()
            .map(id -> ((Number) id).longValue())
            .toList();

        List<Category> categories = categoryIds.isEmpty() ? List.of()
            : em.createQuery("SELECT c FROM Category c WHERE c.id IN :ids", Category.class)
                .setParameter("ids", categoryIds)
                .getResultList();

        List<ProductDtos.CategoryResponse> categoryResponses = categories.stream()
            .map(ProductDtos.CategoryResponse::from)
            .toList();

        return ProductDtos.AdminResponse.from(product, categoryResponses);
    }

    private String buildAuditSnapshot(Product product) {
        try {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("id", product.getId());
            snapshot.put("name", product.getName());
            snapshot.put("basePrice", product.getBasePrice());
            snapshot.put("salePrice", product.getSalePrice());
            snapshot.put("status", product.getStatus());
            return OBJECT_MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize product snapshot", e);
            return "{}";
        }
    }

    private String buildOptionSnapshot(ProductOption option) {
        try {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("id", option.getId());
            snapshot.put("productId", option.getProduct().getId());
            snapshot.put("optionName", option.getOptionName());
            snapshot.put("optionPrice", option.getOptionPrice());
            snapshot.put("status", option.getStatus());
            return OBJECT_MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize option snapshot", e);
            return "{}";
        }
    }

    private void logAudit(String action, String before, String after) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("entity", "Product");
        attrs.put("action", action);
        if (before != null) {
            attrs.put("before", before);
        }
        if (after != null) {
            attrs.put("after", after);
        }
        auditLogger.log("ADMIN_MUTATION", attrs);
    }

    private String toJson(Map<String, Object> data) {
        try {
            return OBJECT_MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit data", e);
            return "{}";
        }
    }
}
