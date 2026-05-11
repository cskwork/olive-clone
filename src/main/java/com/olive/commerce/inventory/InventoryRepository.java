package com.olive.commerce.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

/**
 * Inventory Repository (PRD §20.3).
 */
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * 옵션 ID로 재고 조회.
     */
    Optional<Inventory> findByProductOptionId(Long productOptionId);

    /**
     * 옵션 ID로 재고 조회 (FOR UPDATE - DB 락 fallback용).
     * Redis 다운 시 사용되는 폴백 경로 (PRD §15.4).
     * {@link InventoryService#reserveWithDbLock}에서 호출한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.productOptionId = :optionId")
    Optional<Inventory> findByProductOptionIdForUpdate(@Param("optionId") Long optionId);

    /**
     * 존재 확인.
     */
    boolean existsByProductOptionId(Long productOptionId);
}
