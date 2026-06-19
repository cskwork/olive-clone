package com.olive.commerce.wishlist;

import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import com.olive.commerce.product.ProductRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 찜 목록 서비스 (OLV-W01).
 *
 * <p>add는 멱등(idempotent): 이미 찜한 상품을 다시 추가해도 성공.
 * list는 상품 요약 정보를 batch fetch하여 N+1 없이 반환.
 */
@Service
public class WishlistService {

    private final WishlistItemRepository wishlistItemRepository;
    private final ProductRepository productRepository;
    private final EntityManager em;

    public WishlistService(
        WishlistItemRepository wishlistItemRepository,
        ProductRepository productRepository,
        EntityManager em
    ) {
        this.wishlistItemRepository = wishlistItemRepository;
        this.productRepository = productRepository;
        this.em = em;
    }

    /**
     * 상품 찜 추가 (idempotent).
     *
     * <p>이미 찜한 상품이면 no-op 성공. 존재하지 않는 상품이면 PRODUCT_NOT_FOUND.
     *
     * @param memberId  회원 ID
     * @param productId 상품 ID
     */
    @Transactional
    public void add(Long memberId, Long productId) {
        // 상품 존재 검증
        if (!productRepository.existsById(productId)) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                "Product not found: " + productId);
        }

        // 이미 찜한 경우 no-op
        if (wishlistItemRepository.existsByMemberIdAndProductId(memberId, productId)) {
            return;
        }

        wishlistItemRepository.save(WishlistItem.create(memberId, productId));
    }

    /**
     * 상품 찜 제거.
     *
     * <p>찜하지 않은 상품 제거 요청은 no-op 성공.
     *
     * @param memberId  회원 ID
     * @param productId 상품 ID
     */
    @Transactional
    public void remove(Long memberId, Long productId) {
        wishlistItemRepository.deleteByMemberIdAndProductId(memberId, productId);
    }

    /**
     * 회원 찜 목록 조회 (paginated).
     *
     * <p>상품 요약 정보(이름, 브랜드, 가격, 썸네일)는 batch fetch하여 N+1 방지.
     *
     * @param memberId 회원 ID
     * @param pageable 페이지 정보
     * @return 찜 목록 페이지
     */
    @Transactional(readOnly = true)
    public Page<WishlistDtos.WishlistItemResponse> list(Long memberId, Pageable pageable) {
        Page<WishlistItem> page = wishlistItemRepository.findByMemberId(memberId, pageable);

        if (page.isEmpty()) {
            return Page.empty(pageable);
        }

        List<Long> productIds = page.getContent().stream()
            .map(WishlistItem::getProductId)
            .toList();

        // Batch fetch product summary (name, brand, price, thumbnail) — no N+1.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
            SELECT p.id, b.name, p.name, p.sale_price, p.base_price,
                   (SELECT url FROM product_images WHERE product_id = p.id AND sort_order = 1 LIMIT 1)
            FROM products p
            LEFT JOIN brands b ON p.brand_id = b.id
            WHERE p.id IN :productIds
            """)
            .setParameter("productIds", productIds)
            .getResultList();

        Map<Long, Object[]> productRowById = rows.stream()
            .collect(Collectors.toMap(r -> ((Number) r[0]).longValue(), r -> r));

        List<WishlistDtos.WishlistItemResponse> items = page.getContent().stream()
            .map(item -> {
                Object[] row = productRowById.get(item.getProductId());
                if (row == null) {
                    // 상품이 삭제된 경우 — 기본값으로 반환
                    return new WishlistDtos.WishlistItemResponse(
                        item.getId(),
                        item.getProductId(),
                        null,
                        null,
                        null,
                        null,
                        null
                    );
                }
                BigDecimal salePrice = (BigDecimal) row[3];
                BigDecimal basePrice = (BigDecimal) row[4];
                BigDecimal effectivePrice = salePrice != null ? salePrice : basePrice;

                return new WishlistDtos.WishlistItemResponse(
                    item.getId(),
                    item.getProductId(),
                    (String) row[2],
                    (String) row[1],
                    effectivePrice,
                    basePrice,
                    (String) row[5]
                );
            })
            .toList();

        return new PageImpl<>(items, pageable, page.getTotalElements());
    }
}
