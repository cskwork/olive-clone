package com.olive.commerce.wishlist;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 찜 목록 Repository (OLV-W01).
 */
public interface WishlistItemRepository extends JpaRepository<WishlistItem, Long> {

    /**
     * 회원 ID로 찜 목록 페이지 조회.
     */
    Page<WishlistItem> findByMemberId(Long memberId, Pageable pageable);

    /**
     * 회원 ID + 상품 ID로 찜 아이템 조회.
     */
    Optional<WishlistItem> findByMemberIdAndProductId(Long memberId, Long productId);

    /**
     * 회원 ID + 상품 ID 존재 확인.
     */
    boolean existsByMemberIdAndProductId(Long memberId, Long productId);

    /**
     * 회원 ID + 상품 ID로 찜 아이템 삭제.
     */
    void deleteByMemberIdAndProductId(Long memberId, Long productId);
}
