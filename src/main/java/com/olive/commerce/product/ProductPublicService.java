package com.olive.commerce.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olive.commerce.batch.ProductRanking;
import com.olive.commerce.batch.ProductRankingRepository;
import com.olive.commerce.review.ProductReviewSummary;
import com.olive.commerce.review.ProductReviewSummaryRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 공개 상품 서비스 (OLV-023).
 *
 * cache-aside 패턴: Detail cache는 direct key, List cache는 versioned key.
 * ProductUpdatedEvent listener에서 cache 무효화 처리.
 *
 * A1: reviewCount/avgRating은 product_review_summaries 배치 조회로 채움 (N+1 없음).
 * A2: POPULAR → sales_count DESC, RATING → avg_rating DESC NULLS LAST.
 */
@Service
public class ProductPublicService {

    private static final Logger log = LoggerFactory.getLogger(ProductPublicService.class);

    // Detail cache
    private static final String CACHE_KEY_DETAIL = "cache:product:detail:%d";
    private static final Duration CACHE_TTL_DETAIL = Duration.ofSeconds(60);

    // List cache (versioned)
    private static final String CACHE_KEY_LIST_VERSION = "cache:product:list:version";
    private static final String CACHE_KEY_LIST_PREFIX = "cache:product:list:v";
    private static final Duration CACHE_TTL_LIST = Duration.ofSeconds(30);

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductOptionRepository productOptionRepository;
    private final ProductCategoryMappingRepository categoryMappingRepository;
    private final CategoryRepository categoryRepository;
    private final ProductReviewSummaryRepository reviewSummaryRepository;
    private final ProductRankingRepository productRankingRepository;
    private final EntityManager em;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ProductPublicService(
        ProductRepository productRepository,
        ProductImageRepository productImageRepository,
        ProductOptionRepository productOptionRepository,
        ProductCategoryMappingRepository categoryMappingRepository,
        CategoryRepository categoryRepository,
        ProductReviewSummaryRepository reviewSummaryRepository,
        ProductRankingRepository productRankingRepository,
        EntityManager em,
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper
    ) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.productOptionRepository = productOptionRepository;
        this.categoryMappingRepository = categoryMappingRepository;
        this.categoryRepository = categoryRepository;
        this.reviewSummaryRepository = reviewSummaryRepository;
        this.productRankingRepository = productRankingRepository;
        this.em = em;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 상품 목록 조회 (cache-aside).
     *
     * HIDDEN/STOPPED/DRAFT 상품 제외 (AC3).
     * sort 옵션: popular, latest, price_asc, price_desc, rating
     */
    public Page<ProductDtos.PublicListItem> list(
        Long categoryId,
        Long brandId,
        ProductDtos.SortOption sort,
        int page,
        int size
    ) {
        String cacheKey = buildListCacheKey(categoryId, brandId, sort, page, size);

        // Cache lookup
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return deserializeListPage(cached, page, size);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize cached list: {}", cacheKey, e);
            }
        }

        // Cache miss - build from DB
        Page<ProductDtos.PublicListItem> result = buildListFromDb(categoryId, brandId, sort, page, size);
        cacheList(cacheKey, result);
        return result;
    }

    /**
     * 상품 상세 조회 (cache-aside).
     *
     * Inventory join 없음 (OLV-031 미구현). availableQuantity는 NULL.
     */
    public ProductDtos.PublicDetailResponse getDetail(Long productId) {
        String cacheKey = String.format(CACHE_KEY_DETAIL, productId);

        // Cache lookup
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, ProductDtos.PublicDetailResponse.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize cached detail: {}", cacheKey, e);
            }
        }

        // Cache miss - build from DB
        ProductDtos.PublicDetailResponse result = buildDetailFromDb(productId);
        cacheDetail(cacheKey, result);
        return result;
    }

    /**
     * 랭킹 상위 상품 목록 (rank_score DESC).
     *
     * product_rankings 테이블 기반. 랭킹 데이터가 없으면 빈 페이지를 반환합니다.
     */
    public Page<ProductDtos.RankingItem> rankings(int page, int size) {
        List<ProductRanking> allRankings = productRankingRepository.findAllOrderByRankScoreDesc();
        int total = allRankings.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<ProductRanking> slice = allRankings.subList(fromIndex, toIndex);

        List<ProductDtos.RankingItem> items = enrichRankings(slice);
        return new PageImpl<>(items, PageRequest.of(page, size), total);
    }

    /**
     * 베스트셀러 목록 (sales_count DESC).
     *
     * product_rankings 테이블의 sales_count 기준 정렬.
     */
    public Page<ProductDtos.RankingItem> bestSellers(int page, int size) {
        List<ProductRanking> allRankings = productRankingRepository.findAll(
            org.springframework.data.domain.Sort.by("salesCount").descending()
                .and(org.springframework.data.domain.Sort.by("productId").ascending())
        );
        int total = allRankings.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<ProductRanking> slice = allRankings.subList(fromIndex, toIndex);

        List<ProductDtos.RankingItem> items = enrichRankings(slice);
        return new PageImpl<>(items, PageRequest.of(page, size), total);
    }

    /**
     * ProductRanking 목록에 상품 상세(브랜드명, 가격, 썸네일)와 리뷰 요약을 결합합니다.
     */
    private List<ProductDtos.RankingItem> enrichRankings(List<ProductRanking> rankings) {
        if (rankings.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = rankings.stream().map(ProductRanking::getProductId).toList();

        // Batch-fetch product info via native query (name, price, brand, thumbnail)
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
            SELECT p.id, b.name, p.name, p.sale_price, p.base_price, p.sales_count,
                   (SELECT url FROM product_images WHERE product_id = p.id AND sort_order = 1 LIMIT 1)
            FROM products p
            LEFT JOIN brands b ON p.brand_id = b.id
            WHERE p.id IN :productIds
            """)
            .setParameter("productIds", productIds)
            .getResultList();

        Map<Long, Object[]> productRowById = rows.stream()
            .collect(Collectors.toMap(r -> ((Number) r[0]).longValue(), r -> r));

        Map<Long, ProductReviewSummary> summaryByProductId = reviewSummaryRepository
            .findByProductIdIn(productIds).stream()
            .collect(Collectors.toMap(ProductReviewSummary::getProductId, s -> s));

        Map<Long, ProductRanking> rankingByProductId = rankings.stream()
            .collect(Collectors.toMap(ProductRanking::getProductId, r -> r));

        return productIds.stream()
            .filter(productRowById::containsKey)
            .map(productId -> {
                Object[] row = productRowById.get(productId);
                ProductRanking ranking = rankingByProductId.get(productId);
                ProductReviewSummary summary = summaryByProductId.get(productId);

                BigDecimal salePrice = (BigDecimal) row[3];
                BigDecimal basePrice = (BigDecimal) row[4];
                BigDecimal effectiveSale = salePrice != null ? salePrice : basePrice;
                BigDecimal discountRate = calculateDiscount(basePrice, effectiveSale);
                long salesCount = ((Number) row[5]).longValue();

                BigDecimal rating = summary != null ? summary.getAvgRating() : BigDecimal.ZERO;
                int reviewCount = summary != null ? summary.getReviewCount() : 0;

                return new ProductDtos.RankingItem(
                    productId,
                    (String) row[1],
                    (String) row[2],
                    effectiveSale,
                    basePrice,
                    discountRate,
                    (String) row[6],
                    rating,
                    reviewCount,
                    salesCount,
                    ranking != null ? ranking.getRankScore() : BigDecimal.ZERO
                );
            })
            .toList();
    }

    // ========== Event Listeners (Cache Invalidation) ==========

    /**
     * ProductUpdatedEvent 수신 후 detail cache 무효화.
     *
     * @TransactionalEventListener(phase=AFTER_COMMIT)으로
     * DB 트랜잭션 커밋 후 실행 보장 (AC2).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProductUpdated(ProductUpdatedEvent event) {
        Long productId = event.productId();

        // Invalidate detail cache
        String detailKey = String.format(CACHE_KEY_DETAIL, productId);
        redisTemplate.delete(detailKey);
        log.debug("Invalidated detail cache for product: {}", productId);

        // Bump list version (invalidates all list caches)
        redisTemplate.opsForValue().increment(CACHE_KEY_LIST_VERSION);
        log.debug("Bumped product list version");
    }

    // ========== Private Helpers ==========

    private String buildListCacheKey(Long categoryId, Long brandId, ProductDtos.SortOption sort, int page, int size) {
        String version = redisTemplate.opsForValue().get(CACHE_KEY_LIST_VERSION);
        if (version == null) {
            // Initialize version on first access
            redisTemplate.opsForValue().setIfAbsent(CACHE_KEY_LIST_VERSION, "0");
            version = redisTemplate.opsForValue().get(CACHE_KEY_LIST_VERSION);
        }

        StringBuilder key = new StringBuilder(CACHE_KEY_LIST_PREFIX)
            .append(version)
            .append(":");

        if (categoryId != null) {
            key.append("c").append(categoryId).append(":");
        }
        if (brandId != null) {
            key.append("b").append(brandId).append(":");
        }
        key.append("s").append(sort != null ? sort.name() : "LATEST").append(":");
        key.append("p").append(page).append(":");
        key.append("sz").append(size);

        return key.toString();
    }

    private Page<ProductDtos.PublicListItem> buildListFromDb(
        Long categoryId,
        Long brandId,
        ProductDtos.SortOption sort,
        int page,
        int size
    ) {
        ProductDtos.SortOption effective = sort != null ? sort : ProductDtos.SortOption.LATEST;

        Pageable pageable = PageRequest.of(page, size);

        // Status filter: HIDDEN/STOPPED/DRAFT excluded (AC3).
        // RATING sort joins product_review_summaries; others join is not needed.
        @SuppressWarnings("unchecked")
        List<Object[]> results = em.createNativeQuery(buildListQuery(effective))
            .setParameter("categoryId", categoryId)
            .setParameter("brandId", brandId)
            .setParameter("size", size)
            .setParameter("offset", (long) page * size)
            .getResultList();

        // Collect product IDs for batch review-summary fetch (avoids N+1).
        List<Long> productIds = results.stream()
            .map(row -> ((Number) row[0]).longValue())
            .toList();

        Map<Long, ProductReviewSummary> summaryByProductId = productIds.isEmpty()
            ? Map.of()
            : reviewSummaryRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(ProductReviewSummary::getProductId, s -> s));

        List<ProductDtos.PublicListItem> items = results.stream()
            .map(row -> {
                long productId = ((Number) row[0]).longValue();
                String brandName = (String) row[1];
                String productName = (String) row[2];
                BigDecimal salePrice = (BigDecimal) row[3];
                BigDecimal basePrice = (BigDecimal) row[4];
                String thumbnailUrl = (String) row[5];

                BigDecimal effectiveSalePrice = salePrice != null ? salePrice : basePrice;
                BigDecimal discountRate = calculateDiscount(basePrice, effectiveSalePrice);

                ProductReviewSummary summary = summaryByProductId.get(productId);
                BigDecimal rating = summary != null ? summary.getAvgRating() : BigDecimal.ZERO;
                int reviewCount = summary != null ? summary.getReviewCount() : 0;

                return new ProductDtos.PublicListItem(
                    productId,
                    brandName,
                    productName,
                    effectiveSalePrice,
                    basePrice,
                    discountRate,
                    thumbnailUrl,
                    rating,
                    reviewCount
                );
            })
            .toList();

        // Total count for pagination
        Long total = ((Number) em.createNativeQuery(buildCountQuery(effective))
            .setParameter("categoryId", categoryId)
            .setParameter("brandId", brandId)
            .getSingleResult()).longValue();

        return new PageImpl<>(items, pageable, total);
    }

    /**
     * 정렬 옵션에 따라 목록 쿼리를 생성합니다.
     *
     * RATING sort: LEFT JOIN product_review_summaries → ORDER BY avg_rating DESC NULLS LAST.
     * POPULAR sort: ORDER BY products.sales_count DESC (deterministic with id as tiebreak).
     */
    private String buildListQuery(ProductDtos.SortOption sort) {
        // categoryFilter and brandFilter are plain String constants (not text blocks)
        // so there is no trailing-whitespace stripping at the join point.
        String categoryFilter =
            " AND (CAST(:categoryId AS BIGINT) IS NULL OR p.id IN ("
            + " SELECT pcm.product_id FROM product_category_mapping pcm"
            + " WHERE pcm.category_id = :categoryId))";
        String brandFilter =
            " AND (CAST(:brandId AS BIGINT) IS NULL OR p.brand_id = :brandId)";

        if (sort == ProductDtos.SortOption.RATING) {
            // Built as a single text block — no mid-block concatenation — then
            // the filter strings (which carry their own leading space) are appended.
            return "SELECT p.id, b.name, p.name, p.sale_price, p.base_price,"
                + " (SELECT url FROM product_images WHERE product_id = p.id AND sort_order = 1 LIMIT 1)"
                + " FROM products p"
                + " LEFT JOIN brands b ON p.brand_id = b.id"
                + " LEFT JOIN product_review_summaries prs ON prs.product_id = p.id"
                + " WHERE p.status IN ('ON_SALE', 'SOLD_OUT')"
                + categoryFilter
                + brandFilter
                + " ORDER BY prs.avg_rating DESC NULLS LAST, p.id DESC"
                + " LIMIT :size OFFSET :offset";
        }

        String orderBy = switch (sort) {
            case POPULAR -> "p.sales_count DESC, p.id DESC";
            case LATEST -> "p.id DESC";
            case PRICE_ASC -> "COALESCE(p.sale_price, p.base_price) ASC, p.id DESC";
            case PRICE_DESC -> "COALESCE(p.sale_price, p.base_price) DESC, p.id DESC";
            default -> "p.id DESC";
        };

        return "SELECT p.id, b.name, p.name, p.sale_price, p.base_price,"
            + " (SELECT url FROM product_images WHERE product_id = p.id AND sort_order = 1 LIMIT 1)"
            + " FROM products p"
            + " LEFT JOIN brands b ON p.brand_id = b.id"
            + " WHERE p.status IN ('ON_SALE', 'SOLD_OUT')"
            + categoryFilter
            + brandFilter
            + " ORDER BY " + orderBy
            + " LIMIT :size OFFSET :offset";
    }

    private String buildCountQuery(ProductDtos.SortOption sort) {
        return """
            SELECT COUNT(*)
            FROM products p
            WHERE p.status IN ('ON_SALE', 'SOLD_OUT')
            AND (CAST(:categoryId AS BIGINT) IS NULL OR p.id IN (
                SELECT pcm.product_id FROM product_category_mapping pcm WHERE pcm.category_id = :categoryId
            ))
            AND (CAST(:brandId AS BIGINT) IS NULL OR p.brand_id = :brandId)
            """;
    }

    private BigDecimal calculateDiscount(BigDecimal original, BigDecimal sale) {
        if (sale == null) {
            sale = original;
        }
        if (original.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return original.subtract(sale)
            .divide(original, 4, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .setScale(1, java.math.RoundingMode.HALF_UP);
    }

    private ProductDtos.PublicDetailResponse buildDetailFromDb(Long productId) {
        Product product = productRepository.findByIdWithDetails(productId)
            .orElse(null);

        if (product == null || !isPublicVisible(product)) {
            throw new com.olive.commerce.common.error.BusinessException(
                com.olive.commerce.common.error.ErrorCode.PRODUCT_NOT_FOUND,
                "상품을 찾을 수 없습니다: " + productId
            );
        }

        // Options (inventory 미구현 → availableQuantity = NULL)
        List<ProductDtos.PublicDetailResponse.OptionSummary> options =
            productOptionRepository.findByProductIdOrderByProductId(productId).stream()
                .map(opt -> new ProductDtos.PublicDetailResponse.OptionSummary(
                    opt.getId(),
                    opt.getOptionName(),
                    opt.getOptionPrice(),
                    opt.getStatus(),
                    null // availableQuantity: OLV-031
                ))
                .toList();

        // Images
        List<ProductDtos.PublicDetailResponse.ImageDetail> images =
            productImageRepository.findByProductIdOrderBySortOrder(productId).stream()
                .map(img -> new ProductDtos.PublicDetailResponse.ImageDetail(
                    img.getId(),
                    img.getUrl(),
                    img.getSortOrder(),
                    img.getSortOrder() == 1
                ))
                .toList();

        // Categories (with path)
        @SuppressWarnings("unchecked")
        List<Long> categoryIds = (List<Long>) em.createNativeQuery("""
                SELECT category_id FROM product_category_mapping WHERE product_id = :productId
                """)
            .setParameter("productId", productId)
            .getResultList();

        List<ProductDtos.PublicDetailResponse.CategoryPath> categories =
            categoryIds.isEmpty() ? List.of() :
                em.createQuery("SELECT c FROM Category c WHERE c.id IN :ids", Category.class)
                    .setParameter("ids", categoryIds)
                    .getResultList()
                    .stream()
                    .map(c -> new ProductDtos.PublicDetailResponse.CategoryPath(
                        c.getId(),
                        c.getName(),
                        c.getSlug()
                    ))
                    .toList();

        String brandName = product.getBrand() != null ? product.getBrand().getName() : null;
        String brandLogoUrl = product.getBrand() != null ? product.getBrand().getLogoUrl() : null;

        BigDecimal salePrice = product.getSalePrice() != null
            ? product.getSalePrice()
            : product.getBasePrice();
        BigDecimal originalPrice = product.getBasePrice();
        BigDecimal discountRate = calculateDiscount(originalPrice, salePrice);

        // Review summary (single product — point lookup, no N+1 concern)
        ProductReviewSummary summary = reviewSummaryRepository.findByProductId(productId).orElse(null);
        BigDecimal rating = summary != null ? summary.getAvgRating() : BigDecimal.ZERO;
        int reviewCount = summary != null ? summary.getReviewCount() : 0;

        return new ProductDtos.PublicDetailResponse(
            product.getId(),
            brandName,
            brandLogoUrl,
            product.getName(),
            product.getDescription(),
            salePrice,
            originalPrice,
            discountRate,
            options,
            images,
            categories,
            rating,
            reviewCount
        );
    }

    private boolean isPublicVisible(Product product) {
        Product.ProductStatus status = product.getStatus();
        return status == Product.ProductStatus.ON_SALE || status == Product.ProductStatus.SOLD_OUT;
    }

    private void cacheDetail(String key, ProductDtos.PublicDetailResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, CACHE_TTL_DETAIL);
        } catch (JsonProcessingException e) {
            log.warn("Failed to cache detail: {}", key, e);
        }
    }

    private void cacheList(String key, Page<ProductDtos.PublicListItem> page) {
        try {
            Map<String, Object> serialized = new HashMap<>();
            serialized.put("content", page.getContent());
            serialized.put("totalElements", page.getTotalElements());
            serialized.put("number", page.getNumber());
            serialized.put("size", page.getSize());
            String json = objectMapper.writeValueAsString(serialized);
            redisTemplate.opsForValue().set(key, json, CACHE_TTL_LIST);
        } catch (JsonProcessingException e) {
            log.warn("Failed to cache list: {}", key, e);
        }
    }

    @SuppressWarnings("unchecked")
    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        throw new IllegalArgumentException("Cannot convert " + value + " to BigDecimal");
    }

    private Page<ProductDtos.PublicListItem> deserializeListPage(String json, int page, int size)
        throws JsonProcessingException {
        Map<String, Object> data = objectMapper.readValue(json, Map.class);
        List<Map<String, Object>> content = (List<Map<String, Object>>) data.get("content");

        List<ProductDtos.PublicListItem> items = content.stream()
            .map(row -> new ProductDtos.PublicListItem(
                ((Number) row.get("productId")).longValue(),
                (String) row.get("brandName"),
                (String) row.get("productName"),
                toBigDecimal(row.get("salePrice")),
                toBigDecimal(row.get("originalPrice")),
                toBigDecimal(row.get("discountRate")),
                (String) row.get("thumbnailUrl"),
                toBigDecimal(row.get("rating")),
                (Integer) row.get("reviewCount")
            ))
            .toList();

        long totalElements = ((Number) data.get("totalElements")).longValue();
        return new PageImpl<>(items, PageRequest.of(page, size), totalElements);
    }
}
